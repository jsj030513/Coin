package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.UserAccountRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DelistingRiskMonitorService {
    private final UserAccountRepository users;
    private final TradingSettingsService trading;
    private final LiveBalanceService balances;
    private final ArbitrageEngine engine;
    private final DelistingRiskService delistingRisk;

    public DelistingRiskMonitorService(UserAccountRepository users,
                                       TradingSettingsService trading,
                                       LiveBalanceService balances,
                                       ArbitrageEngine engine,
                                       DelistingRiskService delistingRisk) {
        this.users = users;
        this.trading = trading;
        this.balances = balances;
        this.engine = engine;
        this.delistingRisk = delistingRisk;
    }

    @Scheduled(fixedDelayString = "${delisting-risk.monitor-interval-ms:1800000}",
            initialDelayString = "${delisting-risk.monitor-initial-delay-ms:60000}")
    public void checkHoldings() {
        users.findAll().stream()
                .filter(user -> "USER".equals(user.getRole()))
                .forEach(user -> checkUser(user.getUsername()));
    }

    private void checkUser(String username) {
        try {
            LiveBalanceService.LiveBalanceResponse snapshot = balances.snapshot(username);
            snapshot.balances().stream()
                    .filter(value -> !"KRW".equals(value.asset()))
                    .filter(value -> value.total().signum() > 0)
                    .filter(value -> delistingRisk.risky(value.exchange(), value.asset() + "/KRW"))
                    .forEach(value -> {
                        if (trading.active(username)) trading.emergencyStop(username);
                        delistingRisk.notifyHoldingRisk(username, value.exchange(), value.asset() + "/KRW",
                                estimatedValueKrw(value));
                    });
        } catch (RuntimeException ignored) {
            // Delisting risk alerts must not interrupt the main trading loop.
        }
    }

    private long estimatedValueKrw(LiveBalanceService.LiveAssetBalance balance) {
        BigDecimal bid = engine.currentBid(balance.asset() + "/KRW", balance.exchange());
        BigDecimal price = bid.signum() > 0 ? bid : balance.avgBuyPrice();
        return balance.total().multiply(price).setScale(0, RoundingMode.DOWN).longValue();
    }
}
