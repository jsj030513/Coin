package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.UserAccountRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class KrwRebalanceAlertService {
    private static final Logger log = LoggerFactory.getLogger(KrwRebalanceAlertService.class);
    private static final Map<String, String> BANKS = Map.of(
            "UPBIT", "케이뱅크",
            "BITHUMB", "KB국민은행"
    );

    private final UserAccountRepository users;
    private final LiveBalanceService liveBalances;
    private final TelegramNotificationService telegram;
    private final boolean enabled;
    private final double maxSingleExchangeKrwRatioPercent;
    private final double targetSingleExchangeKrwRatioPercent;
    private final long minTransferKrw;
    private final long transferRoundKrw;

    public KrwRebalanceAlertService(UserAccountRepository users,
                                    LiveBalanceService liveBalances,
                                    TelegramNotificationService telegram,
                                    @Value("${telegram.rebalance-enabled:true}") boolean enabled,
                                    @Value("${telegram.rebalance-max-single-exchange-krw-ratio-percent:75}") double maxSingleExchangeKrwRatioPercent,
                                    @Value("${telegram.rebalance-target-single-exchange-krw-ratio-percent:60}") double targetSingleExchangeKrwRatioPercent,
                                    @Value("${telegram.rebalance-min-transfer-krw:10000}") long minTransferKrw,
                                    @Value("${telegram.rebalance-transfer-round-krw:1000}") long transferRoundKrw) {
        this.users = users;
        this.liveBalances = liveBalances;
        this.telegram = telegram;
        this.enabled = enabled;
        this.maxSingleExchangeKrwRatioPercent = maxSingleExchangeKrwRatioPercent;
        this.targetSingleExchangeKrwRatioPercent = Math.min(
                maxSingleExchangeKrwRatioPercent,
                Math.max(50.0, targetSingleExchangeKrwRatioPercent));
        this.minTransferKrw = minTransferKrw;
        this.transferRoundKrw = Math.max(1, transferRoundKrw);
    }

    @Scheduled(fixedDelayString = "${telegram.rebalance-check-interval-ms:60000}")
    public void check() {
        if (!enabled || !telegram.configured()) return;
        users.findAll().forEach(user -> {
            try {
                checkUser(user.getUsername());
            } catch (Exception error) {
                log.error("KRW rebalance alert check failed | username={}", user.getUsername(), error);
            }
        });
    }

    private void checkUser(String username) {
        LiveBalanceService.LiveBalanceResponse snapshot = liveBalances.snapshot(username);
        boolean bothExchangesConnected = snapshot.statuses().stream()
                .filter(status -> "UPBIT".equals(status.exchange()) || "BITHUMB".equals(status.exchange()))
                .filter(LiveBalanceService.ExchangeBalanceStatus::connected)
                .count() == 2;
        if (!bothExchangesConnected) {
            log.warn("KRW rebalance skipped because both exchange balances were not verified | username={}", username);
            return;
        }
        long upbitKrw = krw(snapshot, "UPBIT");
        long bithumbKrw = krw(snapshot, "BITHUMB");
        long totalKrw = upbitKrw + bithumbKrw;
        if (totalKrw <= 0) {
            telegram.clearKrwRebalanceAlert(username);
            return;
        }

        double upbitRatio = (double) upbitKrw / totalKrw * 100.0;
        double bithumbRatio = (double) bithumbKrw / totalKrw * 100.0;
        if (upbitRatio <= maxSingleExchangeKrwRatioPercent
                && bithumbRatio <= maxSingleExchangeKrwRatioPercent) {
            telegram.clearKrwRebalanceAlert(username);
            return;
        }

        String from = upbitKrw >= bithumbKrw ? "UPBIT" : "BITHUMB";
        String to = from.equals("UPBIT") ? "BITHUMB" : "UPBIT";
        long fromKrw = from.equals("UPBIT") ? upbitKrw : bithumbKrw;
        long toKrw = from.equals("UPBIT") ? bithumbKrw : upbitKrw;
        double fromRatio = from.equals("UPBIT") ? upbitRatio : bithumbRatio;

        long transfer = roundDown(fromKrw - targetMaxKrw(totalKrw));
        if (transfer < minTransferKrw) {
            telegram.clearKrwRebalanceAlert(username);
            return;
        }

        telegram.notifyKrwRebalance(username, from, BANKS.get(from), to, BANKS.get(to),
                transfer, fromKrw, toKrw, totalKrw, fromRatio,
                "한 거래소 KRW 비중이 %.1f%% 기준을 초과했습니다. 이동 후 %.1f%% 안쪽까지만 낮추도록 계산했습니다."
                        .formatted(maxSingleExchangeKrwRatioPercent, targetSingleExchangeKrwRatioPercent));
    }

    private long krw(LiveBalanceService.LiveBalanceResponse snapshot, String exchange) {
        return snapshot.balances().stream()
                .filter(value -> exchange.equals(value.exchange()))
                .filter(value -> "KRW".equals(value.asset()))
                .map(LiveBalanceService.LiveAssetBalance::free)
                .findFirst()
                .orElse(BigDecimal.ZERO)
                .setScale(0, RoundingMode.DOWN)
                .longValue();
    }

    private long roundDown(long amount) {
        return amount <= 0 ? 0 : amount / transferRoundKrw * transferRoundKrw;
    }

    private long targetMaxKrw(long totalKrw) {
        return BigDecimal.valueOf(totalKrw)
                .multiply(BigDecimal.valueOf(targetSingleExchangeKrwRatioPercent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN)
                .longValue();
    }
}
