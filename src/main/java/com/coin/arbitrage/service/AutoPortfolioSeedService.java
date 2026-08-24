package com.coin.arbitrage.service;

import com.coin.arbitrage.config.LiveTradingProperties;
import com.coin.arbitrage.persistence.UserAccountRepository;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AutoPortfolioSeedService {
    private static final Logger log = LoggerFactory.getLogger(AutoPortfolioSeedService.class);
    private final UserAccountRepository users;
    private final TradingSettingsService trading;
    private final PortfolioPlanService plans;
    private final TelegramNotificationService telegram;
    private final LiveTradingProperties properties;
    private final LiveBalanceService balances;
    private final PrincipalProtectionService principalProtection;

    public AutoPortfolioSeedService(UserAccountRepository users, TradingSettingsService trading,
                                    PortfolioPlanService plans, TelegramNotificationService telegram,
                                    LiveTradingProperties properties, LiveBalanceService balances,
                                    PrincipalProtectionService principalProtection) {
        this.users = users;
        this.trading = trading;
        this.plans = plans;
        this.telegram = telegram;
        this.properties = properties;
        this.balances = balances;
        this.principalProtection = principalProtection;
    }

    @Scheduled(fixedDelayString = "${live-trading.auto-portfolio-interval-ms:30000}")
    public void run() {
        if (!properties.enabled() || !properties.autoEnabled() || !properties.seedBuyEnabled()) return;
        users.findAll().forEach(user -> {
            String username = user.getUsername();
            if (!trading.active(username)) return;
            try {
                LiveBalanceService.LiveBalanceResponse snapshot = balances.snapshot(username);
                PrincipalProtectionService.Decision protection = principalProtection.decide(username, snapshot);
                if (protection.protecting()) {
                    log.info("Auto portfolio preparation skipped by principal protection | username={} principal={} equity={} shortage={}",
                            username, protection.principalKrw(), protection.equityKrw(), protection.shortageKrw());
                    return;
                }
                PortfolioPlanService.PortfolioPlan plan = plans.plan(username);
                long readyRecommended = plan.symbols().stream()
                        .filter(PortfolioPlanService.PlanSymbol::ready)
                        .count();
                if (readyRecommended >= plan.settings().targetSymbolCount()) return;
                long preparedCount = preparedSymbolCount(snapshot);
                long upbitSpendable = Math.max(0,
                        plan.upbitCashKrw() - plan.settings().cashReserveKrwPerExchange());
                long bithumbSpendable = Math.max(0,
                        plan.bithumbCashKrw() - plan.settings().cashReserveKrwPerExchange());
                for (PortfolioPlanService.PlanSymbol value : plan.symbols()) {
                    if (value.ready()) continue;
                    boolean upbitHeld = value.upbit().quantity().signum() > 0;
                    boolean bithumbHeld = value.bithumb().quantity().signum() > 0;
                    long upbit = upbitHeld ? 0 : affordable(value.upbitSuggestedBuyKrw(), upbitSpendable);
                    long bithumb = bithumbHeld ? 0 : affordable(value.bithumbSuggestedBuyKrw(), bithumbSpendable);
                    if (!upbitHeld && !bithumbHeld && (upbit == 0 || bithumb == 0)) continue;
                    Candidate candidate = new Candidate(value.symbol(), upbit, bithumb);
                    if (candidate.actionable() && execute(username, candidate)) break;
                }
                log.info("Auto portfolio scan completed | username={} preparedSymbols={} readyRecommended={} targetSymbols={} upbitSpendable={} bithumbSpendable={}",
                        username, preparedCount, readyRecommended, plan.settings().targetSymbolCount(),
                        upbitSpendable, bithumbSpendable);
            } catch (Exception error) {
                log.warn("Auto portfolio preparation skipped | username={} reason={}", username, error.getMessage());
            }
        });
    }

    private static long preparedSymbolCount(LiveBalanceService.LiveBalanceResponse snapshot) {
        java.util.Set<String> upbit = snapshot.balances().stream()
                .filter(value -> "UPBIT".equals(value.exchange()) && !"KRW".equals(value.asset()))
                .filter(value -> value.free().signum() > 0)
                .map(LiveBalanceService.LiveAssetBalance::asset)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> bithumb = snapshot.balances().stream()
                .filter(value -> "BITHUMB".equals(value.exchange()) && !"KRW".equals(value.asset()))
                .filter(value -> value.free().signum() > 0)
                .map(LiveBalanceService.LiveAssetBalance::asset)
                .collect(java.util.stream.Collectors.toSet());
        upbit.retainAll(bithumb);
        return upbit.size();
    }

    private static long affordable(long suggestedKrw, long spendableKrw) {
        long value = Math.min(suggestedKrw, spendableKrw);
        return value >= 5_000 ? value : 0;
    }

    private boolean execute(String username, Candidate candidate) {
        boolean accepted;
        String detail;
        long amount;
        if (candidate.upbitKrw() >= 5_000 && candidate.bithumbKrw() >= 5_000) {
            amount = Math.min(candidate.upbitKrw(), candidate.bithumbKrw());
            PortfolioPlanService.PairSeedBuyDecision result = plans.approvePairSeedBuy(username,
                    new PortfolioPlanService.PairSeedBuyRequest(candidate.symbol(), BigDecimal.valueOf(amount)));
            accepted = result.accepted();
            detail = accepted ? "업비트/빗썸 각각 %,d원 매수 접수".formatted(amount) : result.message();
        } else {
            String exchange = candidate.upbitKrw() >= 5_000 ? "UPBIT" : "BITHUMB";
            amount = "UPBIT".equals(exchange) ? candidate.upbitKrw() : candidate.bithumbKrw();
            PortfolioPlanService.SeedBuyDecision result = plans.approveSeedBuy(username,
                    new PortfolioPlanService.SeedBuyRequest(candidate.symbol(), exchange, BigDecimal.valueOf(amount)));
            accepted = result.accepted();
            detail = accepted ? "%s %,d원 부족분 매수 접수".formatted(exchange, amount) : result.message();
        }
        String message = accepted ? candidate.symbol() + " · " + detail
                : "%s 자동매수 건너뜀 · %s".formatted(candidate.symbol(), detail);
        if (accepted) telegram.notifyAutoPortfolioOrder(username, message);
        log.warn("Auto portfolio decision | username={} symbol={} accepted={} amountKrw={} detail={}",
                username, candidate.symbol(), accepted, amount, detail);
        return accepted;
    }

    private record Candidate(String symbol, long upbitKrw, long bithumbKrw) {
        boolean actionable() { return upbitKrw >= 5_000 || bithumbKrw >= 5_000; }
    }
}
