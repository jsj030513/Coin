package com.coin.arbitrage.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "arbitrage.scan-enabled", havingValue = "true", matchIfMissing = true)
public class ScanScheduler {
    private final ArbitrageEngine engine;

    public ScanScheduler(ArbitrageEngine engine) {
        this.engine = engine;
    }

    @Scheduled(fixedDelayString = "${arbitrage.scan-interval-ms:3000}")
    public void scan() {
        engine.scan();
    }
}
