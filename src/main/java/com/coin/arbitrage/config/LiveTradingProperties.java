package com.coin.arbitrage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("live-trading")
public record LiveTradingProperties(
        boolean enabled,
        boolean autoEnabled,
        boolean seedBuyEnabled,
        long maxOrderKrw,
        long minOrderKrw,
        long inventoryDustFloorKrw,
        double inventoryMaxImbalancePercent,
        double inventoryRebalanceMaxCostPercent,
        boolean krwRecoveryEnabled,
        long krwRecoveryTargetBufferKrw,
        long krwRecoveryCooldownSeconds,
        long cooldownSeconds,
        long cycleTimeoutSeconds,
        int maxOrdersPerRun
) {
}
