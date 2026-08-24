package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.LiveOrderEntity;
import com.coin.arbitrage.persistence.LiveOrderRepository;
import com.coin.arbitrage.persistence.TradeCycleEntity;
import com.coin.arbitrage.persistence.TradeCycleRepository;
import com.coin.arbitrage.persistence.UserAccountRepository;
import com.coin.arbitrage.config.LiveTradingProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeCycleService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final BigDecimal MAX_QUANTITY_MISMATCH_RATIO = new BigDecimal("0.01");
    private final TradeCycleRepository cycles;
    private final LiveOrderRepository orders;
    private final UserAccountRepository users;
    private final RiskSettingsService riskSettings;
    private final FeeProvider fees;
    private final TradingSettingsService trading;
    private final TelegramNotificationService telegram;
    private final LiveTradingProperties liveProperties;

    public TradeCycleService(TradeCycleRepository cycles, LiveOrderRepository orders,
                             UserAccountRepository users, RiskSettingsService riskSettings,
                             FeeProvider fees, TradingSettingsService trading,
                             TelegramNotificationService telegram,
                             LiveTradingProperties liveProperties) {
        this.cycles = cycles;
        this.orders = orders;
        this.users = users;
        this.riskSettings = riskSettings;
        this.fees = fees;
        this.trading = trading;
        this.telegram = telegram;
        this.liveProperties = liveProperties;
    }

    @Transactional
    public TradeCycleEntity begin(String username, String symbol, String buyExchange,
                                  String sellExchange, BigDecimal amount, double expectedProfitKrw) {
        var user = users.findByUsername(username).orElseThrow();
        return cycles.save(new TradeCycleEntity(UUID.randomUUID().toString(), user, symbol,
                buyExchange, sellExchange, amount, BigDecimal.valueOf(expectedProfitKrw)));
    }

    @Transactional
    public void submitted(String cycleId, String buyOrderId, String sellOrderId) {
        TradeCycleEntity cycle = cycles.findById(cycleId).orElseThrow();
        cycle.submitted(buyOrderId, sellOrderId);
    }

    @Transactional
    public void failed(String cycleId, String detail) {
        cycles.findById(cycleId).ifPresent(cycle ->
                cycle.finish(TradeCycleEntity.Status.FAILED, null, detail));
    }

    @Transactional(readOnly = true)
    public Guard guard(String username) {
        RiskSettingsService.Settings settings = riskSettings.get(username);
        long open = cycles.countByUserUsernameAndStatusIn(username,
                List.of(TradeCycleEntity.Status.PENDING, TradeCycleEntity.Status.SUBMITTED));
        BigDecimal dailyLoss = cycles.findByUserUsernameAndCreatedAtAfter(username,
                        java.time.LocalDate.now(SEOUL).atStartOfDay(SEOUL).toInstant()).stream()
                .map(TradeCycleEntity::getRealizedProfitKrw)
                .filter(value -> value != null && value.signum() < 0)
                .map(BigDecimal::abs).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean allowed = open < settings.maxConcurrentPositions()
                && dailyLoss.compareTo(BigDecimal.valueOf(settings.dailyMaxLossKrw())) <= 0;
        return new Guard(allowed, open, dailyLoss, settings.dailyMaxLossKrw());
    }

    @Scheduled(fixedDelayString = "${live-trading.cycle-reconcile-ms:10000}")
    @Transactional
    public void reconcile() {
        expireStaleCycles();
        for (TradeCycleEntity cycle : cycles.findTop100ByStatusOrderByCreatedAtAsc(TradeCycleEntity.Status.SUBMITTED)) {
            LiveOrderEntity buy = orders.findByOrderId(cycle.getBuyOrderId()).orElse(null);
            LiveOrderEntity sell = orders.findByOrderId(cycle.getSellOrderId()).orElse(null);
            if (buy == null || sell == null || !terminal(buy.getStatus()) || !terminal(sell.getStatus())) continue;
            BigDecimal buyQty = buy.getQuantity();
            BigDecimal sellQty = sell.getQuantity();
            BigDecimal realized = realized(cycle, buy, sell);
            boolean valid = buyQty.signum() > 0 && sellQty.signum() > 0
                    && mismatchRatio(buyQty, sellQty).compareTo(MAX_QUANTITY_MISMATCH_RATIO) <= 0;
            if (valid) {
                cycle.finish(TradeCycleEntity.Status.COMPLETED, realized, "양쪽 체결 확인");
            } else {
                String detail = "체결 수량 불일치 · 매수 %s / 매도 %s".formatted(buyQty, sellQty);
                cycle.finish(TradeCycleEntity.Status.MISMATCH, realized, detail);
                trading.emergencyStop(cycle.getUser().getUsername());
                telegram.notifyAutoTradingFailure(cycle.getUser().getUsername(), cycle.getSymbol(),
                        cycle.getBuyExchange(), cycle.getSellExchange(), detail);
            }
        }
    }

    private void expireStaleCycles() {
        Instant cutoff = Instant.now().minusSeconds(Math.max(30, liveProperties.cycleTimeoutSeconds()));
        for (TradeCycleEntity cycle : cycles.findTop100ByStatusInOrderByCreatedAtAsc(
                List.of(TradeCycleEntity.Status.PENDING, TradeCycleEntity.Status.SUBMITTED))) {
            if (!cycle.getCreatedAt().isBefore(cutoff)) continue;
            String detail = "주문 사이클 제한시간 초과 · 거래소 주문 내역 확인 필요";
            cycle.finish(TradeCycleEntity.Status.TIMED_OUT, null, detail);
            trading.emergencyStop(cycle.getUser().getUsername());
            telegram.notifyAutoTradingFailure(cycle.getUser().getUsername(), cycle.getSymbol(),
                    cycle.getBuyExchange(), cycle.getSellExchange(), detail);
        }
    }

    private BigDecimal realized(TradeCycleEntity cycle, LiveOrderEntity buy, LiveOrderEntity sell) {
        BigDecimal buyGross = buy.getExecutedPrice().multiply(buy.getQuantity());
        BigDecimal sellGross = sell.getExecutedPrice().multiply(sell.getQuantity());
        String username = cycle.getUser().getUsername();
        BigDecimal buyCost = buyGross.multiply(BigDecimal.ONE.add(rate(fees.buyFee(username, cycle.getBuyExchange()))));
        BigDecimal sellNet = sellGross.multiply(BigDecimal.ONE.subtract(rate(fees.sellFee(username, cycle.getSellExchange()))));
        return sellNet.subtract(buyCost).setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal rate(double percent) {
        return BigDecimal.valueOf(percent).divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP);
    }

    private static BigDecimal mismatchRatio(BigDecimal left, BigDecimal right) {
        BigDecimal max = left.max(right);
        return max.signum() == 0 ? BigDecimal.ONE
                : left.subtract(right).abs().divide(max, 12, RoundingMode.HALF_UP);
    }

    private static boolean terminal(String status) {
        return "done".equalsIgnoreCase(status) || "cancel".equalsIgnoreCase(status);
    }

    public record Guard(boolean allowed, long openCycles, BigDecimal dailyLossKrw, long dailyLossLimitKrw) { }
}
