package com.coin.arbitrage.domain;

import java.util.List;

public record OrderBookSnapshot(String exchange, String symbol, List<Level> bids, List<Level> asks) {
    public record Level(double price, double quantity) {
    }
}
