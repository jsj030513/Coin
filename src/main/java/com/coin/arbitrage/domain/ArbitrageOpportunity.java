package com.coin.arbitrage.domain;

import java.time.Instant;

public record ArbitrageOpportunity(
        String symbol,
        String buyExchange,
        String sellExchange,
        double buyPrice,
        double sellPrice,
        double baseAmount,
        double investmentKrw,
        double rawProfitPercent,
        double netProfitPercent,
        double expectedProfitKrw,
        double buyQuoteVolume,
        double sellQuoteVolume,
        Instant detectedAt
) {
}
