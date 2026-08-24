package com.coin.arbitrage.service;

import com.coin.arbitrage.config.LiveTradingProperties;
import com.coin.arbitrage.persistence.UserAccountRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

@Service
public class AutoLiveTradingService {
    private static final long INVENTORY_REBALANCE_GLOBAL_COOLDOWN_SECONDS = 3600;
    private static final BigDecimal KRW_RECOVERY_SELL_MIN_SAFETY_MULTIPLIER = new BigDecimal("1.05");
    private static final BigDecimal MIN_ORDER_BUFFER_KRW = BigDecimal.valueOf(1000);
    private static final Logger log = LoggerFactory.getLogger(AutoLiveTradingService.class);
    private final java.util.Map<String, Instant> lastSkipLogAt = new java.util.concurrent.ConcurrentHashMap<>();

    private final UserAccountRepository users;
    private final LiveBalanceService liveBalances;
    private final RiskSettingsService settingsService;
    private final LiveExchangeOrderService orders;
    private final LiveOrderHistoryService orderHistory;
    private final TradingSettingsService tradingSettings;
    private final LiveTradingProperties properties;
    private final TelegramNotificationService telegram;
    private final ArbitrageEngine engine;
    private final TradeCycleService tradeCycles;
    private final PortfolioPlanService portfolioPlans;
    private final UserTradingPreferenceService preferences;
    private final ProfitAdjustmentService profitAdjustments;
    private final PrincipalProtectionService principalProtection;
    private final long manualKrwTransferFeeKrw;
    private final long manualKrwTransferMinKrw;
    private final long manualKrwTransferRoundKrw;

    public AutoLiveTradingService(UserAccountRepository users,
                                  LiveBalanceService liveBalances,
                                  RiskSettingsService settingsService,
                                  LiveExchangeOrderService orders,
                                  LiveOrderHistoryService orderHistory,
                                  TradingSettingsService tradingSettings,
                                  TelegramNotificationService telegram,
                                  ArbitrageEngine engine,
                                  TradeCycleService tradeCycles,
                                  PortfolioPlanService portfolioPlans,
                                  LiveTradingProperties properties,
                                  UserTradingPreferenceService preferences,
                                  ProfitAdjustmentService profitAdjustments,
                                  PrincipalProtectionService principalProtection,
                                  @Value("${live-trading.manual-krw-transfer-fee-krw:1000}") long manualKrwTransferFeeKrw,
                                  @Value("${live-trading.manual-krw-transfer-min-krw:20000}") long manualKrwTransferMinKrw,
                                  @Value("${live-trading.manual-krw-transfer-round-krw:1000}") long manualKrwTransferRoundKrw) {
        this.users = users;
        this.liveBalances = liveBalances;
        this.settingsService = settingsService;
        this.orders = orders;
        this.orderHistory = orderHistory;
        this.tradingSettings = tradingSettings;
        this.telegram = telegram;
        this.engine = engine;
        this.tradeCycles = tradeCycles;
        this.portfolioPlans = portfolioPlans;
        this.properties = properties;
        this.preferences = preferences;
        this.profitAdjustments = profitAdjustments;
        this.principalProtection = principalProtection;
        this.manualKrwTransferFeeKrw = Math.max(0, manualKrwTransferFeeKrw);
        this.manualKrwTransferMinKrw = Math.max(0, manualKrwTransferMinKrw);
        this.manualKrwTransferRoundKrw = Math.max(1, manualKrwTransferRoundKrw);
    }

    @Scheduled(fixedDelayString = "${live-trading.scan-interval-ms:5000}")
    public void run() {
        if (!properties.enabled() || !properties.autoEnabled()) return;
        users.findAll().forEach(user -> {
            RiskSettingsService.Settings settings = settingsService.get(user.getUsername());
            if (!tradingSettings.active(user.getUsername())) return;
            TradeCycleService.Guard guard = tradeCycles.guard(user.getUsername());
            if (!guard.allowed()) {
                if (guard.dailyLossKrw().compareTo(BigDecimal.valueOf(guard.dailyLossLimitKrw())) > 0) {
                    tradingSettings.emergencyStop(user.getUsername());
                    telegram.notifyAutoTradingFailure(user.getUsername(), "ALL", "-", "-",
                            "일일 손실 한도 도달: %s원 / %d원".formatted(
                                    guard.dailyLossKrw().setScale(0, java.math.RoundingMode.UP), guard.dailyLossLimitKrw()));
                }
                return;
            }
            int submitted = 0;
            try {
                LiveBalanceService.LiveBalanceResponse snapshot = liveBalances.snapshot(user.getUsername());
                long minExchangeKrw = preferences.get(user.getUsername()).minExchangeKrw();
                PrincipalProtectionService.Decision protection = principalProtection.decide(user.getUsername(), snapshot);
                if (!protection.protecting() && rebalanceInventory(user.getUsername(), snapshot)) return;
                java.util.List<LiveBalanceService.LiveOpportunityReadiness> candidates = snapshot.readiness().stream()
                        .sorted(java.util.Comparator
                                .comparing((LiveBalanceService.LiveOpportunityReadiness value) ->
                                                krwBalanceImprovement(snapshot, value),
                                        java.util.Comparator.reverseOrder())
                                .thenComparing((LiveBalanceService.LiveOpportunityReadiness value) ->
                                                inventoryImprovement(snapshot, value, value.requiredBase()),
                                        java.util.Comparator.reverseOrder())
                                .thenComparing(LiveBalanceService.LiveOpportunityReadiness::netProfitPercent,
                                        java.util.Comparator.reverseOrder()))
                        .toList();
                if (recoverKrwIfNeeded(user.getUsername(), snapshot, candidates, settings)) return;
                if (!protection.protecting() && prepareSellInventoryIfNeeded(user.getUsername(), snapshot, candidates, settings,
                        minExchangeKrw)) return;
                for (LiveBalanceService.LiveOpportunityReadiness candidate : candidates) {
                    if (submitted >= Math.max(1, properties.maxOrdersPerRun())) break;
                    if (!candidate.executable()) {
                        notifyKrwShortageIfNeeded(user.getUsername(), snapshot, candidate);
                        logSkippedCandidate(user.getUsername(), candidate);
                        continue;
                    }
                    if (candidate.detectedAt() == null || candidate.detectedAt().isBefore(Instant.now().minusSeconds(15))) continue;
                    if (candidate.netProfitPercent() < settings.minProfitPercent()) continue;
                    if (candidate.netProfitPercent() > settings.maxProfitPercent()) continue;
                    if (candidate.requiredKrw().compareTo(BigDecimal.ZERO) <= 0) continue;
                    if (orderHistory.recentlySubmitted(user.getUsername(), candidate.buyExchange(),
                            candidate.symbol(), "AUTO_ARBITRAGE", properties.cooldownSeconds())) continue;
                    if (!orders.orderReady(user.getUsername(), candidate.buyExchange())
                            || !orders.orderReady(user.getUsername(), candidate.sellExchange())) continue;
                    BigDecimal amount = candidate.requiredKrw()
                            .min(BigDecimal.valueOf(properties.maxOrderKrw()))
                            .max(BigDecimal.valueOf(properties.minOrderKrw()));
                    double scaledProfit = candidate.expectedProfitKrw()
                            * amount.doubleValue() / candidate.requiredKrw().doubleValue();
                    if (scaledProfit < settings.minExpectedProfitKrw()) continue;
                    if (amount.compareTo(candidate.availableKrw()) > 0) {
                        notifyKrwShortageIfNeeded(user.getUsername(), snapshot, candidate, amount);
                        log.info("Auto trade candidate skipped by KRW | username={} symbol={} exchange={} amount={} availableKrw={}",
                                user.getUsername(), candidate.symbol(), candidate.buyExchange(), amount, candidate.availableKrw());
                        continue;
                    }
                    if (candidate.availableKrw().subtract(amount)
                            .compareTo(BigDecimal.valueOf(minExchangeKrw)) < 0) continue;
                    ArbitrageEngine.RevalidatedOpportunity current = engine.revalidate(user.getUsername(), candidate.symbol(),
                            candidate.buyExchange(), candidate.sellExchange(), amount);
                    if (!current.executable()) continue;
                    if (current.netProfitPercent() < settings.minProfitPercent()
                            || current.netProfitPercent() > settings.maxProfitPercent()
                            || current.expectedProfitKrw() < settings.minExpectedProfitKrw()) continue;
                    BigDecimal sellQuantity = current.sellQuantity().setScale(12, java.math.RoundingMode.DOWN);
                    if (sellQuantity.compareTo(candidate.availableBase()) > 0) continue;
                    BigDecimal remainingQuantity = candidate.availableBase().subtract(sellQuantity);
                    if (remainingQuantity.signum() < 0) continue;
                    BigDecimal remainingValueKrw = remainingValueKrw(current, remainingQuantity);
                    if (isUnsellableDust(remainingValueKrw)) {
                        BigDecimal expandedAmount = amount.multiply(candidate.availableBase())
                                .divide(sellQuantity, 0, java.math.RoundingMode.UP)
                                .min(BigDecimal.valueOf(properties.maxOrderKrw()));
                        if (expandedAmount.compareTo(amount) > 0
                                && expandedAmount.compareTo(candidate.availableKrw()) <= 0) {
                            ArbitrageEngine.RevalidatedOpportunity expanded = engine.revalidate(
                                    user.getUsername(), candidate.symbol(), candidate.buyExchange(),
                                    candidate.sellExchange(), expandedAmount);
                            BigDecimal expandedSellQuantity = expanded.sellQuantity()
                                    .setScale(12, java.math.RoundingMode.DOWN);
                            BigDecimal expandedRemaining = candidate.availableBase().subtract(expandedSellQuantity);
                            BigDecimal expandedRemainingValue = remainingValueKrw(expanded, expandedRemaining);
                            if (expanded.executable()
                                    && expanded.netProfitPercent() >= settings.minProfitPercent()
                                    && expanded.netProfitPercent() <= settings.maxProfitPercent()
                                    && expanded.expectedProfitKrw() >= settings.minExpectedProfitKrw()
                                    && expandedSellQuantity.compareTo(candidate.availableBase()) <= 0
                                    && !isMeaningfulUnsellableDust(expandedRemainingValue)) {
                                if (candidate.availableKrw().subtract(expandedAmount)
                                        .compareTo(BigDecimal.valueOf(minExchangeKrw)) < 0) {
                                    log.warn("Auto trade expands amount to clear dust below cash reserve | username={} symbol={} route={}->{} amount={} expandedAmount={} availableKrw={} minReserve={}",
                                            user.getUsername(), candidate.symbol(), candidate.buyExchange(),
                                            candidate.sellExchange(), amount, expandedAmount,
                                            candidate.availableKrw(), minExchangeKrw);
                                }
                                amount = expandedAmount;
                                current = expanded;
                                sellQuantity = expandedSellQuantity;
                            } else if (!isMeaningfulUnsellableDust(remainingValueKrw)) {
                                log.info("Auto trade allows tiny dust | username={} symbol={} exchange={} remainingKrw={} floorKrw={}",
                                        user.getUsername(), candidate.symbol(), candidate.sellExchange(),
                                        remainingValueKrw, properties.minOrderKrw());
                            } else {
                                log.info("Auto trade blocked by inventory floor | username={} symbol={} exchange={} remainingKrw={} floorKrw={}",
                                        user.getUsername(), candidate.symbol(), candidate.sellExchange(), remainingValueKrw,
                                        properties.minOrderKrw());
                                continue;
                            }
                        } else {
                            if (!isMeaningfulUnsellableDust(remainingValueKrw)) {
                                log.info("Auto trade allows tiny dust | username={} symbol={} exchange={} remainingKrw={} floorKrw={}",
                                        user.getUsername(), candidate.symbol(), candidate.sellExchange(),
                                        remainingValueKrw, properties.minOrderKrw());
                            } else {
                                log.info("Auto trade blocked by inventory floor | username={} symbol={} exchange={} remainingKrw={} floorKrw={}",
                                        user.getUsername(), candidate.symbol(), candidate.sellExchange(), remainingValueKrw,
                                        properties.minOrderKrw());
                                continue;
                            }
                        }
                    }
                    if (!inventoryDirectionAllowed(snapshot, candidate, sellQuantity,
                            properties.inventoryMaxImbalancePercent())) {
                        log.warn("Auto trade inventory direction guard bypassed | username={} symbol={} route={}->{} sellQuantity={} maxImbalancePercent={}",
                                user.getUsername(), candidate.symbol(), candidate.buyExchange(),
                                candidate.sellExchange(), sellQuantity, properties.inventoryMaxImbalancePercent());
                    }
                    if (!orders.orderChanceReady(user.getUsername(), candidate.buyExchange(),
                            candidate.symbol(), true, amount)) {
                        log.warn("Auto trade blocked by buy order chance | username={} symbol={} exchange={} amount={}",
                                user.getUsername(), candidate.symbol(), candidate.buyExchange(), amount);
                        continue;
                    }
                    if (!orders.orderChanceReady(user.getUsername(), candidate.sellExchange(),
                            candidate.symbol(), false, sellQuantity, current.sellQuoteAmount())) {
                        log.warn("Auto trade blocked by sell order chance | username={} symbol={} exchange={} quantity={} estimatedTotalKrw={}",
                                user.getUsername(), candidate.symbol(), candidate.sellExchange(),
                                sellQuantity, current.sellQuoteAmount());
                        continue;
                    }
                    var cycle = tradeCycles.begin(user.getUsername(), candidate.symbol(),
                            candidate.buyExchange(), candidate.sellExchange(), amount, current.expectedProfitKrw());
                    boolean buyAttempted = false;
                    try {
                        buyAttempted = true;
                        var buy = orders.buyMarket(user.getUsername(), candidate.buyExchange(), candidate.symbol(), amount);
                        orderHistory.record(user.getUsername(), "BUY", amount, "AUTO_ARBITRAGE", buy);
                        var sell = orders.sellMarket(user.getUsername(), candidate.sellExchange(), candidate.symbol(), sellQuantity);
                        orderHistory.record(user.getUsername(), "SELL", BigDecimal.ZERO, "AUTO_ARBITRAGE", sell);
                        tradeCycles.submitted(cycle.getId(), buy.orderId(), sell.orderId());
                    } catch (RuntimeException orderError) {
                        tradeCycles.failed(cycle.getId(), orderError.getMessage());
                        if (orderRejectedBeforeAcceptance(orderError)) {
                            log.warn("Auto live arbitrage rejected before acceptance | username={} symbol={} route={}->{} reason={}",
                                    user.getUsername(), candidate.symbol(), candidate.buyExchange(),
                                    candidate.sellExchange(), orderError.getMessage());
                            continue;
                        }
                        if (buyAttempted) {
                            tradingSettings.emergencyStop(user.getUsername());
                            telegram.notifyAutoTradingFailure(user.getUsername(), candidate.symbol(),
                                    candidate.buyExchange(), candidate.sellExchange(),
                                    "주문 결과가 불확실할 수 있어 비상정지: " + orderError.getMessage());
                        }
                        throw orderError;
                    }
                    submitted++;
                    log.warn("Auto live arbitrage submitted | username={} symbol={} route={}->{} amountKrw={}",
                            user.getUsername(), candidate.symbol(), candidate.buyExchange(), candidate.sellExchange(), amount);
                }
            } catch (Exception error) {
                log.error("Auto live trading failed | username={}", user.getUsername(), error);
            }
        });
    }

    private void logSkippedCandidate(String username, LiveBalanceService.LiveOpportunityReadiness candidate) {
        String key = username + ":" + candidate.symbol() + ":" + candidate.buyExchange()
                + ":" + candidate.sellExchange() + ":" + candidate.reason();
        Instant now = Instant.now();
        Instant previous = lastSkipLogAt.get(key);
        if (previous != null && previous.plusSeconds(60).isAfter(now)) return;
        lastSkipLogAt.put(key, now);
        log.info("Auto trade candidate skipped | username={} symbol={} route={}->{} reason={} requiredKrw={} availableKrw={} requiredBase={} availableBase={} net={} expectedKrw={}",
                username, candidate.symbol(), candidate.buyExchange(), candidate.sellExchange(),
                candidate.reason(), candidate.requiredKrw(), candidate.availableKrw(),
                candidate.requiredBase(), candidate.availableBase(),
                candidate.netProfitPercent(), candidate.expectedProfitKrw());
    }

    private void notifyKrwShortageIfNeeded(String username, LiveBalanceService.LiveBalanceResponse snapshot,
                                           LiveBalanceService.LiveOpportunityReadiness candidate) {
        notifyKrwShortageIfNeeded(username, snapshot, candidate,
                candidate.requiredKrw().max(BigDecimal.valueOf(properties.minOrderKrw())));
    }

    private void notifyKrwShortageIfNeeded(String username, LiveBalanceService.LiveBalanceResponse snapshot,
                                           LiveBalanceService.LiveOpportunityReadiness candidate,
                                           BigDecimal neededKrw) {
        if (candidate.availableKrw().compareTo(neededKrw) >= 0) return;
        BigDecimal sourceKrw = assetBalance(snapshot, candidate.sellExchange(), "KRW");
        telegram.notifyAutoTradeBlockedByKrw(username, candidate.symbol(), candidate.buyExchange(),
                candidate.sellExchange(), neededKrw.setScale(0, java.math.RoundingMode.UP).longValue(),
                candidate.availableKrw().setScale(0, java.math.RoundingMode.DOWN).longValue(),
                sourceKrw.setScale(0, java.math.RoundingMode.DOWN).longValue(),
                candidate.netProfitPercent(), candidate.expectedProfitKrw());
    }

    private boolean rebalanceInventory(String username, LiveBalanceService.LiveBalanceResponse snapshot) {
        if (orderHistory.recentlySubmitted(username, "AUTO_INVENTORY_REBALANCE",
                INVENTORY_REBALANCE_GLOBAL_COOLDOWN_SECONDS)) return false;
        java.util.Map<String, BigDecimal> upbit = balancesByAsset(snapshot, "UPBIT");
        java.util.Map<String, BigDecimal> bithumb = balancesByAsset(snapshot, "BITHUMB");
        return upbit.keySet().stream()
                .filter(bithumb::containsKey)
                .filter(asset -> !"KRW".equals(asset))
                .map(asset -> new InventoryPair(asset, upbit.get(asset), bithumb.get(asset)))
                .filter(pair -> pair.upbit().signum() > 0 && pair.bithumb().signum() > 0)
                .sorted(java.util.Comparator.comparingDouble(InventoryPair::imbalancePercent).reversed())
                .filter(pair -> pair.imbalancePercent() > properties.inventoryMaxImbalancePercent())
                .anyMatch(pair -> executeInventoryRebalance(username, snapshot, pair));
    }

    private boolean executeInventoryRebalance(String username, LiveBalanceService.LiveBalanceResponse snapshot,
                                              InventoryPair pair) {
        String buyExchange = pair.upbit().compareTo(pair.bithumb()) < 0 ? "UPBIT" : "BITHUMB";
        String sellExchange = "UPBIT".equals(buyExchange) ? "BITHUMB" : "UPBIT";
        BigDecimal lowQuantity = "UPBIT".equals(buyExchange) ? pair.upbit() : pair.bithumb();
        BigDecimal highQuantity = "UPBIT".equals(sellExchange) ? pair.upbit() : pair.bithumb();
        String symbol = pair.asset() + "/KRW";
        if (orderHistory.recentlySubmitted(username, buyExchange, symbol,
                "AUTO_INVENTORY_REBALANCE", properties.cooldownSeconds())) return false;
        BigDecimal availableKrw = assetBalance(snapshot, buyExchange, "KRW/KRW");
        BigDecimal minimumCash = BigDecimal.valueOf(preferences.get(username).minExchangeKrw());
        BigDecimal spendableKrw = availableKrw.subtract(minimumCash).max(BigDecimal.ZERO);
        BigDecimal maxAmount = BigDecimal.valueOf(properties.maxOrderKrw()).min(spendableKrw);
        if (maxAmount.compareTo(BigDecimal.valueOf(properties.minOrderKrw())) < 0) return false;
        ArbitrageEngine.RevalidatedOpportunity estimate = engine.revalidate(username, symbol, buyExchange, sellExchange, maxAmount);
        if (!estimate.executable() || estimate.sellQuantity().signum() <= 0) return false;
        BigDecimal targetQuantity = highQuantity.subtract(lowQuantity)
                .divide(BigDecimal.valueOf(2), 12, java.math.RoundingMode.DOWN);
        BigDecimal amount = maxAmount;
        if (estimate.sellQuantity().compareTo(targetQuantity) > 0) {
            amount = maxAmount.multiply(targetQuantity)
                    .divide(estimate.sellQuantity(), 0, java.math.RoundingMode.DOWN);
        }
        if (amount.compareTo(BigDecimal.valueOf(properties.minOrderKrw())) < 0) return false;
        ArbitrageEngine.RevalidatedOpportunity current = engine.revalidate(username, symbol, buyExchange, sellExchange, amount);
        if (!current.executable()
                || current.netProfitPercent() < -Math.abs(properties.inventoryRebalanceMaxCostPercent())) return false;
        if (current.expectedProfitKrw() < 0) {
            log.info("Inventory rebalance skipped by capital protection | username={} symbol={} route={}->{} expectedProfitKrw={}",
                    username, symbol, buyExchange, sellExchange, current.expectedProfitKrw());
            return false;
        }
        BigDecimal recoveryAmount = amount;
        double recoveryProfit = snapshot.readiness().stream()
                .filter(value -> symbol.equals(value.symbol()))
                .filter(value -> sellExchange.equalsIgnoreCase(value.buyExchange()))
                .filter(value -> buyExchange.equalsIgnoreCase(value.sellExchange()))
                .filter(LiveBalanceService.LiveOpportunityReadiness::executable)
                .mapToDouble(value -> value.expectedProfitKrw()
                        * recoveryAmount.doubleValue() / Math.max(1.0, value.requiredKrw().doubleValue()))
                .max().orElse(0.0);
        double combinedProfit = current.expectedProfitKrw() + recoveryProfit;
        if (combinedProfit < settingsService.get(username).minExpectedProfitKrw()) {
            log.info("Inventory rebalance deferred | username={} symbol={} rebalanceProfitKrw={} recoveryProfitKrw={} combinedKrw={}",
                    username, symbol, current.expectedProfitKrw(), recoveryProfit, combinedProfit);
            return false;
        }
        BigDecimal sellQuantity = current.sellQuantity().setScale(12, java.math.RoundingMode.DOWN);
        if (sellQuantity.compareTo(highQuantity) > 0) return false;
        BigDecimal remainingValueKrw = current.sellQuoteAmount()
                .multiply(highQuantity.subtract(sellQuantity))
                .divide(current.sellQuantity(), 0, java.math.RoundingMode.DOWN);
        if (remainingValueKrw.signum() > 0
                && remainingValueKrw.compareTo(BigDecimal.valueOf(properties.minOrderKrw())) < 0) return false;
        if (!orders.orderChanceReady(username, buyExchange, symbol, true, amount)) return false;
        if (!orders.orderChanceReady(username, sellExchange, symbol, false,
                sellQuantity, current.sellQuoteAmount())) return false;
        var cycle = tradeCycles.begin(username, symbol, buyExchange, sellExchange,
                amount, current.expectedProfitKrw());
        boolean attempted = false;
        try {
            attempted = true;
            var buy = orders.buyMarket(username, buyExchange, symbol, amount);
            orderHistory.record(username, "BUY", amount, "AUTO_INVENTORY_REBALANCE", buy);
            var sell = orders.sellMarket(username, sellExchange, symbol, sellQuantity);
            orderHistory.record(username, "SELL", BigDecimal.ZERO, "AUTO_INVENTORY_REBALANCE", sell);
            tradeCycles.submitted(cycle.getId(), buy.orderId(), sell.orderId());
            telegram.notifyAutoPortfolioOrder(username,
                    "%s 수량 균형 복원 · %s 매수 / %s 매도 · %,d원"
                            .formatted(symbol, buyExchange, sellExchange, amount.longValue()));
            log.warn("Inventory rebalance submitted | username={} symbol={} route={}->{} amountKrw={}",
                    username, symbol, buyExchange, sellExchange, amount);
            return true;
        } catch (RuntimeException error) {
            tradeCycles.failed(cycle.getId(), error.getMessage());
            if (orderRejectedBeforeAcceptance(error)) {
                log.warn("Inventory rebalance rejected before acceptance | username={} symbol={} route={}->{} reason={}",
                        username, symbol, buyExchange, sellExchange, error.getMessage());
                return false;
            }
            if (attempted) {
                tradingSettings.emergencyStop(username);
                telegram.notifyAutoTradingFailure(username, symbol, buyExchange, sellExchange,
                        "재고 균형 복원 주문 결과 확인 필요: " + error.getMessage());
            }
            return false;
        }
    }

    private boolean recoverKrwIfNeeded(String username, LiveBalanceService.LiveBalanceResponse snapshot,
                                       java.util.List<LiveBalanceService.LiveOpportunityReadiness> candidates,
                                       RiskSettingsService.Settings settings) {
        if (!properties.krwRecoveryEnabled()) return false;
        if (orderHistory.recentlySubmitted(username, "AUTO_KRW_RECOVERY",
                properties.krwRecoveryCooldownSeconds())) return false;
        return candidates.stream()
                .filter(candidate -> !candidate.executable())
                .filter(candidate -> candidate.reason() != null && candidate.reason().contains("KRW 부족"))
                .filter(candidate -> candidate.detectedAt() != null
                        && !candidate.detectedAt().isBefore(Instant.now().minusSeconds(30)))
                .filter(candidate -> candidate.netProfitPercent() >= settings.minProfitPercent())
                .filter(candidate -> candidate.netProfitPercent() <= settings.maxProfitPercent())
                .filter(candidate -> candidate.expectedProfitKrw() >= settings.minExpectedProfitKrw())
                .sorted(java.util.Comparator
                        .comparingDouble(LiveBalanceService.LiveOpportunityReadiness::expectedProfitKrw).reversed()
                        .thenComparing(LiveBalanceService.LiveOpportunityReadiness::netProfitPercent,
                                java.util.Comparator.reverseOrder()))
                .anyMatch(candidate -> executeKrwRecovery(username, snapshot, candidate, candidates));
    }

    private boolean prepareSellInventoryIfNeeded(String username, LiveBalanceService.LiveBalanceResponse snapshot,
                                                 java.util.List<LiveBalanceService.LiveOpportunityReadiness> candidates,
                                                 RiskSettingsService.Settings settings,
                                                 long minExchangeKrw) {
        if (!properties.seedBuyEnabled()) return false;
        if (orderHistory.recentlySubmitted(username, "AUTO_SELL_INVENTORY_SEED",
                properties.cooldownSeconds())) return false;
        return candidates.stream()
                .filter(candidate -> !candidate.executable())
                .filter(candidate -> candidate.reason() != null && candidate.reason().contains("매도 거래소 코인 부족"))
                .filter(candidate -> candidate.detectedAt() != null
                        && !candidate.detectedAt().isBefore(Instant.now().minusSeconds(30)))
                .filter(candidate -> candidate.availableKrw().compareTo(candidate.requiredKrw()) >= 0)
                .filter(candidate -> candidate.netProfitPercent() >= settings.minProfitPercent())
                .filter(candidate -> candidate.netProfitPercent() <= settings.maxProfitPercent())
                .filter(candidate -> candidate.expectedProfitKrw() >= settings.minExpectedProfitKrw())
                .sorted(java.util.Comparator
                        .comparingDouble(LiveBalanceService.LiveOpportunityReadiness::expectedProfitKrw).reversed()
                        .thenComparing(LiveBalanceService.LiveOpportunityReadiness::netProfitPercent,
                                java.util.Comparator.reverseOrder()))
                .anyMatch(candidate -> executeSellInventorySeed(username, snapshot, candidate, minExchangeKrw));
    }

    private boolean executeSellInventorySeed(String username, LiveBalanceService.LiveBalanceResponse snapshot,
                                             LiveBalanceService.LiveOpportunityReadiness blocked,
                                             long minExchangeKrw) {
        String exchange = blocked.sellExchange();
        String symbol = blocked.symbol();
        if (!orders.orderReady(username, exchange)) {
            log.warn("Sell inventory seed skipped by order permission | username={} symbol={} exchange={}",
                    username, symbol, exchange);
            return false;
        }
        if (orderHistory.recentlySubmitted(username, exchange, symbol,
                "AUTO_SELL_INVENTORY_SEED", properties.cooldownSeconds())) return false;
        BigDecimal availableKrw = assetBalance(snapshot, exchange, "KRW");
        BigDecimal spendableKrw = availableKrw.subtract(BigDecimal.valueOf(minExchangeKrw)).max(BigDecimal.ZERO);
        BigDecimal safeSpendableKrw = spendableKrw
                .subtract(BigDecimal.valueOf(Math.max(100, properties.minOrderKrw() / 50)))
                .max(BigDecimal.ZERO);
        BigDecimal minimumUsefulInventoryKrw = BigDecimal.valueOf(properties.minOrderKrw());
        BigDecimal targetInventoryKrw = blocked.requiredKrw()
                .add(BigDecimal.valueOf(properties.inventoryDustFloorKrw()))
                .max(minimumUsefulInventoryKrw);
        BigDecimal compactInventoryKrw = blocked.requiredKrw()
                .max(minimumUsefulInventoryKrw)
                .add(MIN_ORDER_BUFFER_KRW);
        BigDecimal maxSeedAmount = BigDecimal.valueOf(properties.maxOrderKrw())
                .min(safeSpendableKrw);
        BigDecimal amount = targetInventoryKrw
                .min(maxSeedAmount)
                .setScale(0, java.math.RoundingMode.DOWN);
        if (amount.compareTo(targetInventoryKrw) < 0 && maxSeedAmount.compareTo(minimumUsefulInventoryKrw) >= 0) {
            amount = compactInventoryKrw.min(maxSeedAmount)
                    .setScale(0, java.math.RoundingMode.DOWN);
        }
        if (amount.compareTo(minimumUsefulInventoryKrw) < 0) {
            log.info("Sell inventory seed skipped by KRW reserve | username={} symbol={} exchange={} amount={} spendableKrw={} availableKrw={} minReserve={}",
                    username, symbol, exchange, amount, spendableKrw, availableKrw, minExchangeKrw);
            return false;
        }
        try {
            if (!orders.orderChanceReady(username, exchange, symbol, true, amount)) {
                log.warn("Sell inventory seed skipped by order chance | username={} symbol={} exchange={} amount={}",
                        username, symbol, exchange, amount);
                return false;
            }
            var buy = orders.buySeedMarket(username, exchange, symbol, amount);
            orderHistory.record(username, "BUY", amount, "AUTO_SELL_INVENTORY_SEED", buy);
            telegram.notifyAutoPortfolioOrder(username,
                    "%s 매도재고 자동확보 · %s %,d원 매수 · 막힌 경로 %s 매수 → %s 매도"
                            .formatted(symbol, exchange, amount.longValue(),
                                    blocked.buyExchange(), blocked.sellExchange()));
            log.warn("Auto sell inventory seed submitted | username={} symbol={} exchange={} amountKrw={} blockedRoute={}->{} expectedKrw={} net={}",
                    username, symbol, exchange, amount, blocked.buyExchange(), blocked.sellExchange(),
                    blocked.expectedProfitKrw(), blocked.netProfitPercent());
            return true;
        } catch (RuntimeException error) {
            if (orderRejectedBeforeAcceptance(error)) {
                log.warn("Auto sell inventory seed rejected before acceptance | username={} symbol={} exchange={} reason={}",
                        username, symbol, exchange, error.getMessage());
                return true;
            }
            tradingSettings.emergencyStop(username);
            telegram.notifyAutoTradingFailure(username, symbol, blocked.buyExchange(), blocked.sellExchange(),
                    "매도 재고 자동확보 주문 결과 확인 필요: " + error.getMessage());
            log.error("Auto sell inventory seed failed | username={} exchange={} symbol={}",
                    username, exchange, symbol, error);
            return true;
        }
    }

    private boolean executeKrwRecovery(String username, LiveBalanceService.LiveBalanceResponse snapshot,
                                       LiveBalanceService.LiveOpportunityReadiness blocked,
                                       java.util.List<LiveBalanceService.LiveOpportunityReadiness> candidates) {
        String exchange = blocked.buyExchange();
        if (!orders.orderReady(username, exchange)) return false;
        BigDecimal availableKrw = assetBalance(snapshot, exchange, "KRW");
        BigDecimal targetKrw = BigDecimal.valueOf(properties.minOrderKrw())
                .add(BigDecimal.valueOf(Math.max(0, properties.krwRecoveryTargetBufferKrw())));
        if (availableKrw.compareTo(targetKrw) >= 0) return false;
        BigDecimal minimumSafeSellQuote = minimumSafeRecoverySellQuote();
        BigDecimal neededQuote = targetKrw.subtract(availableKrw)
                .max(minimumSafeSellQuote);
        java.util.Set<String> protectedSymbols = protectedRecoverySymbols(candidates, exchange);
        protectedSymbols.add(blocked.symbol());
        return snapshot.balances().stream()
                .filter(balance -> exchange.equalsIgnoreCase(balance.exchange()))
                .filter(balance -> !"KRW".equalsIgnoreCase(balance.asset()))
                .filter(balance -> balance.free() != null && balance.free().signum() > 0)
                .map(balance -> recoveryCandidate(exchange, balance, protectedSymbols, neededQuote,
                        minimumSafeSellQuote))
                .flatMap(java.util.Optional::stream)
                .sorted(java.util.Comparator
                        .comparing((KrwRecoveryCandidate value) ->
                                        value.fullQuoteAmount().compareTo(neededQuote) >= 0 ? 0 : 1)
                        .thenComparing(KrwRecoveryCandidate::sellQuoteAmount))
                .anyMatch(candidate -> submitKrwRecovery(username, snapshot, blocked, candidate, neededQuote, availableKrw));
    }

    private java.util.Optional<KrwRecoveryCandidate> recoveryCandidate(
            String exchange, LiveBalanceService.LiveAssetBalance balance,
            java.util.Set<String> protectedSymbols, BigDecimal neededQuote,
            BigDecimal minimumSafeSellQuote) {
        String symbol = balance.asset().toUpperCase(java.util.Locale.ROOT) + "/KRW";
        if (protectedSymbols.contains(symbol)) return java.util.Optional.empty();
        ArbitrageEngine.SellEstimate full = engine.estimateSell(symbol, exchange, balance.free());
        if (!full.executable() || full.quoteAmount().compareTo(minimumSafeSellQuote) < 0) {
            return java.util.Optional.empty();
        }
        if (full.averagePrice().signum() <= 0) return java.util.Optional.empty();
        BigDecimal quoteToSell = full.quoteAmount().min(neededQuote.max(minimumSafeSellQuote));
        BigDecimal quantity = balance.free().multiply(quoteToSell)
                .divide(full.quoteAmount(), 12, java.math.RoundingMode.DOWN);
        BigDecimal remainingQuote = full.quoteAmount().subtract(quoteToSell);
        if (remainingQuote.signum() > 0
                && remainingQuote.compareTo(BigDecimal.valueOf(properties.inventoryDustFloorKrw())) < 0) {
            quantity = balance.free();
            quoteToSell = full.quoteAmount();
        }
        if (quantity.signum() <= 0) return java.util.Optional.empty();
        ArbitrageEngine.SellEstimate partial = engine.estimateSell(symbol, exchange, quantity);
        if (!partial.executable()
                || partial.quoteAmount().compareTo(minimumSafeSellQuote) < 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new KrwRecoveryCandidate(exchange, symbol, quantity.setScale(12,
                java.math.RoundingMode.DOWN), partial.quoteAmount(), full.quoteAmount()));
    }

    private boolean submitKrwRecovery(String username, LiveBalanceService.LiveBalanceResponse snapshot,
                                      LiveBalanceService.LiveOpportunityReadiness blocked,
                                      KrwRecoveryCandidate candidate, BigDecimal neededQuote,
                                      BigDecimal availableKrw) {
        try {
            ProfitAdjustmentService.EstimatedAdjustment estimatedAdjustment =
                    profitAdjustments.estimateAutoKrwRecovery(username, candidate.exchange(),
                            candidate.symbol(), candidate.quantity(), candidate.sellQuoteAmount());
            if (!estimatedAdjustment.costBasisKnown()) {
                log.warn("Auto KRW recovery skipped by unknown cost basis | username={} sellSymbol={} exchange={} blockedSymbol={}",
                        username, candidate.symbol(), candidate.exchange(), blocked.symbol());
                notifyManualTransferIfPossible(username, snapshot, blocked, neededQuote,
                        null, "자동 매도 손익 기준을 알 수 없어 직접 원화 이동 확인이 더 안전합니다.");
                return false;
            }
            if (estimatedAdjustment.realizedProfitKrw().signum() < 0) {
                log.warn("Auto KRW recovery skipped by capital protection | username={} sellSymbol={} exchange={} estimatedRealizedKrw={} blockedSymbol={} blockedExpectedKrw={}",
                        username, candidate.symbol(), candidate.exchange(),
                        estimatedAdjustment.realizedProfitKrw(), blocked.symbol(), blocked.expectedProfitKrw());
                notifyManualTransferIfPossible(username, snapshot, blocked, neededQuote,
                        estimatedAdjustment, "자동 원화 확보가 손실로 예상되어 원금 보호 기준상 실행하지 않습니다.");
                return false;
            }
            if (manualTransferCheaper(username, snapshot, blocked, neededQuote, estimatedAdjustment)) {
                return false;
            }
            BigDecimal maxAcceptableLoss = BigDecimal.valueOf(Math.max(0, blocked.expectedProfitKrw()));
            if (estimatedAdjustment.realizedProfitKrw().signum() < 0
                    && estimatedAdjustment.realizedProfitKrw().abs().compareTo(maxAcceptableLoss) > 0) {
                log.warn("Auto KRW recovery skipped by loss guard | username={} sellSymbol={} exchange={} estimatedRealizedKrw={} blockedSymbol={} blockedExpectedKrw={}",
                        username, candidate.symbol(), candidate.exchange(),
                        estimatedAdjustment.realizedProfitKrw(), blocked.symbol(), blocked.expectedProfitKrw());
                notifyManualTransferIfPossible(username, snapshot, blocked, neededQuote,
                        estimatedAdjustment, "자동 원화 확보 예상 손실이 살리려는 기회 수익보다 큽니다.");
                return false;
            }
            if (!orders.orderChanceReady(username, candidate.exchange(), candidate.symbol(), false,
                    candidate.quantity(), candidate.sellQuoteAmount())) return false;
            var sell = orders.sellMarket(username, candidate.exchange(), candidate.symbol(), candidate.quantity());
            orderHistory.record(username, "SELL", BigDecimal.ZERO, "AUTO_KRW_RECOVERY", sell);
            BigDecimal realized = profitAdjustments.recordAutoKrwRecovery(username, candidate.exchange(),
                    candidate.symbol(), candidate.quantity(), candidate.sellQuoteAmount());
            telegram.notifyAutoKrwRecovery(username, candidate.symbol(), candidate.exchange(),
                    candidate.sellQuoteAmount().setScale(0, java.math.RoundingMode.DOWN).longValue(),
                    availableKrw.setScale(0, java.math.RoundingMode.DOWN).longValue(),
                    neededQuote.setScale(0, java.math.RoundingMode.UP).longValue(),
                    realized.setScale(0, java.math.RoundingMode.HALF_UP).longValue(),
                    blocked.symbol(), blocked.buyExchange(), blocked.sellExchange());
            log.warn("Auto KRW recovery submitted | username={} exchange={} soldSymbol={} quoteKrw={} realizedKrw={} blockedSymbol={} route={}->{} availableKrw={} neededQuote={}",
                    username, candidate.exchange(), candidate.symbol(), candidate.sellQuoteAmount(),
                    realized, blocked.symbol(), blocked.buyExchange(), blocked.sellExchange(), availableKrw, neededQuote);
            return true;
        } catch (RuntimeException error) {
            if (orderRejectedBeforeAcceptance(error)) {
                log.warn("Auto KRW recovery candidate rejected before acceptance | username={} exchange={} symbol={} reason={}",
                        username, candidate.exchange(), candidate.symbol(), error.getMessage());
                return false;
            }
            tradingSettings.emergencyStop(username);
            telegram.notifyAutoTradingFailure(username, blocked.symbol(), blocked.buyExchange(), blocked.sellExchange(),
                    "원화 자동 확보 매도 주문 결과 확인 필요: " + error.getMessage());
            log.error("Auto KRW recovery failed | username={} exchange={} symbol={}",
                    username, candidate.exchange(), candidate.symbol(), error);
            return true;
        }
    }

    private boolean manualTransferCheaper(String username, LiveBalanceService.LiveBalanceResponse snapshot,
                                          LiveBalanceService.LiveOpportunityReadiness blocked,
                                          BigDecimal neededQuote,
                                          ProfitAdjustmentService.EstimatedAdjustment estimatedAdjustment) {
        BigDecimal autoCostKrw = estimatedAdjustment.realizedProfitKrw().signum() < 0
                ? estimatedAdjustment.realizedProfitKrw().abs()
                : BigDecimal.ZERO;
        BigDecimal manualCostKrw = BigDecimal.valueOf(manualKrwTransferFeeKrw);
        if (manualCostKrw.compareTo(autoCostKrw) > 0) return false;
        return notifyManualTransferIfPossible(username, snapshot, blocked, neededQuote, estimatedAdjustment,
                "직접 원화 이동 예상 비용이 자동 원화 확보 예상 손실보다 낮습니다.");
    }

    private boolean notifyManualTransferIfPossible(String username, LiveBalanceService.LiveBalanceResponse snapshot,
                                                   LiveBalanceService.LiveOpportunityReadiness blocked,
                                                   BigDecimal neededQuote,
                                                   ProfitAdjustmentService.EstimatedAdjustment estimatedAdjustment,
                                                   String reason) {
        String toExchange = blocked.buyExchange();
        String fromExchange = blocked.sellExchange();
        BigDecimal sourceKrw = assetBalance(snapshot, fromExchange, "KRW");
        BigDecimal targetAmount = roundUp(neededQuote.max(BigDecimal.valueOf(properties.minOrderKrw())),
                manualKrwTransferRoundKrw);
        if (targetAmount.compareTo(BigDecimal.valueOf(manualKrwTransferMinKrw)) < 0) {
            log.info("Manual KRW transfer not recommended because target amount is below minimum | username={} from={} to={} targetAmount={} minTransfer={}",
                    username, fromExchange, toExchange, targetAmount, manualKrwTransferMinKrw);
            return false;
        }
        BigDecimal transferWithFee = targetAmount.add(BigDecimal.valueOf(manualKrwTransferFeeKrw));
        if (sourceKrw.compareTo(transferWithFee) < 0) {
            log.info("Manual KRW transfer not recommended because source KRW is insufficient | username={} from={} to={} sourceKrw={} targetAmount={}",
                    username, fromExchange, toExchange, sourceKrw, transferWithFee);
            return false;
        }
        BigDecimal autoCostKrw = estimatedAdjustment == null
                ? null
                : (estimatedAdjustment.realizedProfitKrw().signum() < 0
                ? estimatedAdjustment.realizedProfitKrw().abs()
                : BigDecimal.ZERO);
        telegram.notifyManualKrwTransferPreferred(username, fromExchange, bank(fromExchange),
                toExchange, bank(toExchange), targetAmount.longValue(),
                sourceKrw.setScale(0, java.math.RoundingMode.DOWN).longValue(),
                assetBalance(snapshot, toExchange, "KRW").setScale(0, java.math.RoundingMode.DOWN).longValue(),
                manualKrwTransferFeeKrw,
                autoCostKrw == null ? null : autoCostKrw.setScale(0, java.math.RoundingMode.UP).longValue(),
                blocked.symbol(), blocked.expectedProfitKrw(), reason);
        log.warn("Manual KRW transfer preferred over auto recovery | username={} from={} to={} amount={} manualFee={} autoCost={} blockedSymbol={} reason={}",
                username, fromExchange, toExchange, targetAmount, manualKrwTransferFeeKrw,
                autoCostKrw, blocked.symbol(), reason);
        return true;
    }

    private static BigDecimal roundUp(BigDecimal amount, long unit) {
        BigDecimal safeUnit = BigDecimal.valueOf(Math.max(1, unit));
        return amount.divide(safeUnit, 0, java.math.RoundingMode.UP).multiply(safeUnit);
    }

    private static String bank(String exchange) {
        return switch (exchange.toUpperCase(java.util.Locale.ROOT)) {
            case "UPBIT" -> "케이뱅크";
            case "BITHUMB" -> "KB국민은행";
            default -> "연결 은행";
        };
    }

    private BigDecimal minimumSafeRecoverySellQuote() {
        return BigDecimal.valueOf(properties.minOrderKrw())
                .multiply(KRW_RECOVERY_SELL_MIN_SAFETY_MULTIPLIER)
                .setScale(0, java.math.RoundingMode.UP);
    }

    private static boolean orderRejectedBeforeAcceptance(RuntimeException error) {
        String message = error.getMessage();
        if (message == null) return false;
        return message.contains("HTTP 400") || message.contains("HTTP 429")
                || message.contains("under_min_total") || message.contains("insufficient_funds");
    }

    private static java.util.Set<String> protectedRecoverySymbols(
            java.util.List<LiveBalanceService.LiveOpportunityReadiness> candidates, String exchange) {
        return candidates.stream()
                .filter(candidate -> exchange.equalsIgnoreCase(candidate.sellExchange()))
                .filter(candidate -> candidate.detectedAt() != null
                        && !candidate.detectedAt().isBefore(Instant.now().minusSeconds(60)))
                .filter(candidate -> candidate.availableBase().compareTo(candidate.requiredBase()) >= 0)
                .map(LiveBalanceService.LiveOpportunityReadiness::symbol)
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
    }

    private static BigDecimal inventoryImprovement(LiveBalanceService.LiveBalanceResponse snapshot,
                                                   LiveBalanceService.LiveOpportunityReadiness candidate,
                                                   BigDecimal quantity) {
        BigDecimal buyBalance = assetBalance(snapshot, candidate.buyExchange(), candidate.symbol());
        BigDecimal sellBalance = assetBalance(snapshot, candidate.sellExchange(), candidate.symbol());
        BigDecimal before = buyBalance.subtract(sellBalance).abs();
        BigDecimal after = buyBalance.add(quantity).subtract(sellBalance.subtract(quantity)).abs();
        return before.subtract(after);
    }

    private static BigDecimal krwBalanceImprovement(LiveBalanceService.LiveBalanceResponse snapshot,
                                                    LiveBalanceService.LiveOpportunityReadiness candidate) {
        BigDecimal buyKrw = assetBalance(snapshot, candidate.buyExchange(), "KRW");
        BigDecimal sellKrw = assetBalance(snapshot, candidate.sellExchange(), "KRW");
        BigDecimal amount = candidate.requiredKrw();
        BigDecimal before = buyKrw.subtract(sellKrw).abs();
        BigDecimal after = buyKrw.subtract(amount).subtract(sellKrw.add(amount)).abs();
        return before.subtract(after);
    }

    private static BigDecimal remainingValueKrw(ArbitrageEngine.RevalidatedOpportunity current,
                                                BigDecimal remainingQuantity) {
        if (current.sellQuantity().signum() <= 0 || remainingQuantity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return current.sellQuoteAmount()
                .multiply(remainingQuantity)
                .divide(current.sellQuantity(), 0, java.math.RoundingMode.DOWN);
    }

    private boolean isUnsellableDust(BigDecimal valueKrw) {
        return valueKrw != null
                && valueKrw.signum() > 0
                && valueKrw.compareTo(BigDecimal.valueOf(properties.minOrderKrw())) < 0;
    }

    private boolean isMeaningfulUnsellableDust(BigDecimal valueKrw) {
        return isUnsellableDust(valueKrw)
                && valueKrw.compareTo(MIN_ORDER_BUFFER_KRW) > 0;
    }

    static boolean inventoryDirectionAllowed(LiveBalanceService.LiveBalanceResponse snapshot,
                                             LiveBalanceService.LiveOpportunityReadiness candidate,
                                             BigDecimal quantity, double maxImbalancePercent) {
        BigDecimal buyBalance = assetBalance(snapshot, candidate.buyExchange(), candidate.symbol());
        BigDecimal sellBalance = assetBalance(snapshot, candidate.sellExchange(), candidate.symbol());
        BigDecimal total = buyBalance.add(sellBalance);
        if (total.signum() <= 0) return false;
        BigDecimal before = buyBalance.subtract(sellBalance).abs();
        BigDecimal after = buyBalance.add(quantity).subtract(sellBalance.subtract(quantity)).abs();
        double afterRatio = after.multiply(BigDecimal.valueOf(100))
                .divide(total, 8, java.math.RoundingMode.HALF_UP).doubleValue();
        return afterRatio <= Math.max(0, maxImbalancePercent) || after.compareTo(before) < 0;
    }

    private static BigDecimal assetBalance(LiveBalanceService.LiveBalanceResponse snapshot,
                                           String exchange, String symbol) {
        String asset = symbol.replace("/KRW", "");
        return snapshot.balances().stream()
                .filter(value -> exchange.equalsIgnoreCase(value.exchange()))
                .filter(value -> asset.equalsIgnoreCase(value.asset()))
                .map(LiveBalanceService.LiveAssetBalance::free)
                .findFirst().orElse(BigDecimal.ZERO);
    }

    private static java.util.Map<String, BigDecimal> balancesByAsset(
            LiveBalanceService.LiveBalanceResponse snapshot, String exchange) {
        return snapshot.balances().stream()
                .filter(value -> exchange.equalsIgnoreCase(value.exchange()))
                .collect(java.util.stream.Collectors.toMap(
                        LiveBalanceService.LiveAssetBalance::asset,
                        LiveBalanceService.LiveAssetBalance::free,
                        BigDecimal::add));
    }

    private static java.util.Set<String> preparedSymbols(LiveBalanceService.LiveBalanceResponse snapshot) {
        java.util.Set<String> upbit = balancesByAsset(snapshot, "UPBIT").entrySet().stream()
                .filter(entry -> !"KRW".equals(entry.getKey()) && entry.getValue().signum() > 0)
                .map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> bithumb = balancesByAsset(snapshot, "BITHUMB").entrySet().stream()
                .filter(entry -> !"KRW".equals(entry.getKey()) && entry.getValue().signum() > 0)
                .map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        upbit.retainAll(bithumb);
        return upbit.stream().map(asset -> asset + "/KRW")
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private record InventoryPair(String asset, BigDecimal upbit, BigDecimal bithumb) {
        double imbalancePercent() {
            BigDecimal total = upbit.add(bithumb);
            return total.signum() <= 0 ? 0 : upbit.subtract(bithumb).abs()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(total, 8, java.math.RoundingMode.HALF_UP).doubleValue();
        }
    }

    private record KrwRecoveryCandidate(String exchange, String symbol, BigDecimal quantity,
                                        BigDecimal sellQuoteAmount, BigDecimal fullQuoteAmount) { }
}
