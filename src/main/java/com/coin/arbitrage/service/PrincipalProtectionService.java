package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.PrincipalDepositRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PrincipalProtectionService {
    private final PrincipalDepositRepository deposits;
    private final ArbitrageEngine engine;
    private final TelegramNotificationService telegram;
    private final Set<String> enabledUsernames;
    private final long equityBufferKrw;
    private final ConcurrentHashMap<String, Boolean> alertState = new ConcurrentHashMap<>();

    public PrincipalProtectionService(PrincipalDepositRepository deposits, ArbitrageEngine engine,
                                      TelegramNotificationService telegram,
                                      @Value("${principal-protection.enabled-usernames:onlymine}") String enabledUsernames,
                                      @Value("${principal-protection.equity-buffer-krw:0}") long equityBufferKrw) {
        this.deposits = deposits;
        this.engine = engine;
        this.telegram = telegram;
        this.enabledUsernames = parseEnabledUsernames(enabledUsernames);
        this.equityBufferKrw = Math.max(0, equityBufferKrw);
    }

    public Decision decide(String username, LiveBalanceService.LiveBalanceResponse snapshot) {
        if (!enabledFor(username)) return Decision.disabled();
        BigDecimal principal = deposits.sumByUsername(username);
        if (principal.signum() <= 0) return Decision.disabled();
        BigDecimal equity = equity(snapshot);
        BigDecimal threshold = principal.add(BigDecimal.valueOf(equityBufferKrw));
        boolean protect = equity.compareTo(threshold) < 0;
        Decision decision = new Decision(true, protect, principal, equity, threshold,
                threshold.subtract(equity).max(BigDecimal.ZERO));
        notifyIfStateChanged(username, decision);
        return decision;
    }

    private void notifyIfStateChanged(String username, Decision decision) {
        Boolean previous = alertState.put(username.toLowerCase(java.util.Locale.ROOT), decision.protecting());
        if (previous != null && previous == decision.protecting()) return;
        if (decision.protecting()) {
            telegram.notifyPrincipalProtection(username,
                    decision.principalKrw().setScale(0, RoundingMode.DOWN).longValue(),
                    decision.equityKrw().setScale(0, RoundingMode.DOWN).longValue(),
                    decision.shortageKrw().setScale(0, RoundingMode.UP).longValue(),
                    "원금보다 평가액이 낮아 새 코인 매수·재고 보충·리밸런싱을 멈췄습니다. 기존 보유분으로 가능한 차익거래만 시도합니다.");
        } else {
            telegram.notifyPrincipalProtection(username,
                    decision.principalKrw().setScale(0, RoundingMode.DOWN).longValue(),
                    decision.equityKrw().setScale(0, RoundingMode.DOWN).longValue(),
                    0,
                    "평가액이 원금 기준을 회복해 원금 방어 모드를 해제했습니다.");
        }
    }

    private BigDecimal equity(LiveBalanceService.LiveBalanceResponse snapshot) {
        BigDecimal total = BigDecimal.ZERO;
        for (LiveBalanceService.LiveAssetBalance balance : snapshot.balances()) {
            if ("KRW".equals(balance.asset())) {
                total = total.add(balance.total());
                continue;
            }
            String symbol = balance.asset() + "/KRW";
            BigDecimal bid = engine.currentBid(symbol, balance.exchange());
            BigDecimal price = bid.signum() > 0 ? bid : balance.avgBuyPrice();
            if (price.signum() > 0) total = total.add(balance.total().multiply(price));
        }
        return total.setScale(0, RoundingMode.DOWN);
    }

    private boolean enabledFor(String username) {
        return enabledUsernames.contains(username.toLowerCase(java.util.Locale.ROOT));
    }

    private static Set<String> parseEnabledUsernames(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(item -> item.toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public record Decision(boolean enabled, boolean protecting, BigDecimal principalKrw,
                           BigDecimal equityKrw, BigDecimal thresholdKrw,
                           BigDecimal shortageKrw) {
        static Decision disabled() {
            return new Decision(false, false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }
}
