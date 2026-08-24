package com.coin.arbitrage.domain;

import java.math.BigDecimal;
import java.util.Map;

public record ExchangeBalance(String exchange, Map<String, BigDecimal> assets) {
    public ExchangeBalance {
        assets = Map.copyOf(assets);
    }
}
