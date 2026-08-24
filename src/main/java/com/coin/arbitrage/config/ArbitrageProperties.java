package com.coin.arbitrage.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("arbitrage")
public record ArbitrageProperties(
        long minQuoteVolume24h,
        double feePercent,
        double minProfitPercent,
        double maxProfitPercent,
        long orderAmountKrw,
        long maxOrderAmountKrw,
        long dailyMaxLossKrw,
        int maxConcurrentPositions,
        long opportunityCooldownSeconds,
        int requestTimeoutSeconds,
        int maxRetries,
        List<String> enabledExchanges
) {
}
