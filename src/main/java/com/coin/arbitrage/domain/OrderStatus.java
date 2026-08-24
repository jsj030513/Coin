package com.coin.arbitrage.domain;

import java.math.BigDecimal;

public record OrderStatus(String orderId, String status, BigDecimal executedQuantity,
                          BigDecimal executedPrice) { }
