package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.ExternalFeeRepository;
import com.coin.arbitrage.persistence.LiveOrderEntity;
import com.coin.arbitrage.persistence.LiveOrderRepository;
import com.coin.arbitrage.persistence.PrincipalDepositRepository;
import com.coin.arbitrage.persistence.ProfitAdjustmentRepository;
import com.coin.arbitrage.persistence.TradeCycleEntity;
import com.coin.arbitrage.persistence.TradeCycleRepository;
import com.coin.arbitrage.persistence.UserAccountRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TradingHealthMonitorService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final UserAccountRepository users;
    private final TradingSettingsService trading;
    private final TradeCycleRepository cycles;
    private final LiveOrderRepository orders;
    private final ProfitAdjustmentRepository adjustments;
    private final ExternalFeeRepository fees;
    private final PrincipalDepositRepository deposits;
    private final LiveBalanceService balances;
    private final TelegramNotificationService telegram;
    private final Set<String> enabledUsernames;
    private final long noTradeAlertHours;
    private final long noTradeAlertCooldownHours;
    private final Map<String, Instant> lastNoTradeAlertAt = new ConcurrentHashMap<>();

    public TradingHealthMonitorService(UserAccountRepository users, TradingSettingsService trading,
                                       TradeCycleRepository cycles, LiveOrderRepository orders,
                                       ProfitAdjustmentRepository adjustments, ExternalFeeRepository fees,
                                       PrincipalDepositRepository deposits, LiveBalanceService balances,
                                       TelegramNotificationService telegram,
                                       @Value("${trading-health.enabled-usernames:onlymine}") String enabledUsernames,
                                       @Value("${trading-health.no-trade-alert-hours:6}") long noTradeAlertHours,
                                       @Value("${trading-health.no-trade-alert-cooldown-hours:6}") long noTradeAlertCooldownHours) {
        this.users = users;
        this.trading = trading;
        this.cycles = cycles;
        this.orders = orders;
        this.adjustments = adjustments;
        this.fees = fees;
        this.deposits = deposits;
        this.balances = balances;
        this.telegram = telegram;
        this.enabledUsernames = parseEnabledUsernames(enabledUsernames);
        this.noTradeAlertHours = Math.max(1, noTradeAlertHours);
        this.noTradeAlertCooldownHours = Math.max(1, noTradeAlertCooldownHours);
    }

    @Scheduled(fixedDelayString = "${trading-health.no-trade-check-interval-ms:1800000}",
            initialDelayString = "${trading-health.no-trade-initial-delay-ms:180000}")
    public void checkNoTrade() {
        users.findAll().stream()
                .filter(user -> "USER".equals(user.getRole()))
                .filter(user -> enabledFor(user.getUsername()))
                .filter(user -> trading.active(user.getUsername()))
                .forEach(user -> checkNoTrade(user.getUsername()));
    }

    @Scheduled(cron = "${trading-health.seven-day-report-cron:0 55 23 * * *}",
            zone = "${trading-health.report-zone:Asia/Seoul}")
    public void sendSevenDayReports() {
        users.findAll().stream()
                .filter(user -> "USER".equals(user.getRole()))
                .filter(user -> enabledFor(user.getUsername()))
                .forEach(user -> sendSevenDayReport(user.getUsername()));
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

    private void checkNoTrade(String username) {
        try {
            Instant cutoff = Instant.now().minus(Duration.ofHours(noTradeAlertHours));
            long recentCompleted = cycles.countByUserUsernameAndStatusAndCreatedAtAfter(
                    username, TradeCycleEntity.Status.COMPLETED, cutoff);
            if (recentCompleted > 0) return;
            Instant previous = lastNoTradeAlertAt.get(username);
            if (previous != null && previous.plus(Duration.ofHours(noTradeAlertCooldownHours)).isAfter(Instant.now())) {
                return;
            }
            Summary7d summary = summary7d(username);
            LiveBalanceService.LiveBalanceResponse snapshot = balances.snapshot(username);
            long upbitKrw = krw(snapshot, "UPBIT");
            long bithumbKrw = krw(snapshot, "BITHUMB");
            long executable = snapshot.readiness().stream().filter(LiveBalanceService.LiveOpportunityReadiness::executable).count();
            String lastOrder = orders.findTopByUserUsernameAndStatusOrderByCreatedAtDesc(username, "done")
                    .map(this::lastOrderText)
                    .orElse("체결 주문 기록 없음");
            lastNoTradeAlertAt.put(username, Instant.now());
            telegram.sendNoTradeAlert(username, noTradeAlertHours, summary.completedCycles(),
                    summary.doneOrders(), summary.netProfit(), upbitKrw, bithumbKrw, executable, lastOrder);
        } catch (RuntimeException ignored) {
            // Health alerts must never interrupt trading.
        }
    }

    private void sendSevenDayReport(String username) {
        try {
            Summary7d summary = summary7d(username);
            telegram.sendSevenDayHealthReport(username, summary.completedCycles(), summary.failedCycles(),
                    summary.doneOrders(), summary.grossProfit(), summary.externalFees(),
                    summary.netProfit(), deposits.sumByUsername(username));
        } catch (RuntimeException ignored) {
            // Reporting failure should not affect trading.
        }
    }

    private Summary7d summary7d(String username) {
        Instant since = Instant.now().minus(Duration.ofDays(7));
        LocalDate sinceDate = LocalDate.now(SEOUL).minusDays(6);
        BigDecimal gross = cycles.sumRealizedProfitSince(username, since)
                .add(adjustments.sumByUsernameSince(username, since));
        BigDecimal externalFee = BigDecimal.ZERO;
        for (int i = 0; i < 7; i++) {
            externalFee = externalFee.add(fees.sumByUsernameAndFeeDate(username, sinceDate.plusDays(i)));
        }
        long completed = cycles.countByUserUsernameAndStatusAndCreatedAtAfter(
                username, TradeCycleEntity.Status.COMPLETED, since);
        long failed = cycles.countByUserUsernameAndStatusAndCreatedAtAfter(
                username, TradeCycleEntity.Status.FAILED, since);
        long doneOrders = orders.countByUserUsernameAndStatusAndCreatedAtAfter(username, "done", since);
        return new Summary7d(completed, failed, doneOrders, gross, externalFee, gross.subtract(externalFee));
    }

    private long krw(LiveBalanceService.LiveBalanceResponse snapshot, String exchange) {
        return snapshot.balances().stream()
                .filter(value -> exchange.equals(value.exchange()))
                .filter(value -> "KRW".equals(value.asset()))
                .map(LiveBalanceService.LiveAssetBalance::free)
                .findFirst()
                .orElse(BigDecimal.ZERO)
                .setScale(0, java.math.RoundingMode.DOWN)
                .longValue();
    }

    private String lastOrderText(LiveOrderEntity order) {
        return "%s %s %s · %s · %s".formatted(order.getExchange(), order.getSide(),
                order.getSymbol(), order.getStatus(), order.getCreatedAt().atZone(SEOUL).toLocalDateTime());
    }

    private record Summary7d(long completedCycles, long failedCycles, long doneOrders,
                             BigDecimal grossProfit, BigDecimal externalFees, BigDecimal netProfit) { }
}
