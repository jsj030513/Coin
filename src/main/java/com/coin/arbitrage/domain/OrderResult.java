package com.coin.arbitrage.domain;

import java.math.BigDecimal;

public record OrderResult(String orderId, String exchange, String symbol, BigDecimal quantity,
                          BigDecimal executedPrice, String status) { }
