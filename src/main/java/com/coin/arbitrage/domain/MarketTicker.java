package com.coin.arbitrage.domain;

public record MarketTicker(
        String exchange,
        String symbol,
        double bid,
        double ask,
        double last,
        double volume,
        double quoteVolume
) {
    public boolean valid() {
        return bid > 0 && ask > 0 && last > 0;
    }
}
