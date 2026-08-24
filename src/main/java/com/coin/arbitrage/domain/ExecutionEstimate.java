package com.coin.arbitrage.domain;

public record ExecutionEstimate(double averagePrice, double baseAmount, double quoteAmount, boolean fullyFilled) {
}
