package com.coin.arbitrage.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LiveExchangeOrderServiceTest {
    @Test
    void rejectsSellBelowExchangeMinimumTotal() {
        boolean ready = LiveExchangeOrderService.sellConstraintsSatisfied(
                new BigDecimal("1.0"), new BigDecimal("4999"),
                new BigDecimal("5000"), new BigDecimal("2.0"));

        assertThat(ready).isFalse();
    }

    @Test
    void acceptsSellWhenValueAndBalanceAreEnough() {
        boolean ready = LiveExchangeOrderService.sellConstraintsSatisfied(
                new BigDecimal("1.0"), new BigDecimal("5100"),
                new BigDecimal("5000"), new BigDecimal("2.0"));

        assertThat(ready).isTrue();
    }
}
