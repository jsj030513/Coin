package com.coin.arbitrage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("risk")
public record RiskProperties(double minExpectedProfitKrw) { }
