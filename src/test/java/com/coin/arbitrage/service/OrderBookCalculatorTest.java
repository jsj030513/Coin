package com.coin.arbitrage.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.coin.arbitrage.domain.ExecutionEstimate;
import com.coin.arbitrage.domain.OrderBookSnapshot.Level;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderBookCalculatorTest {
    private final OrderBookCalculator calculator = new OrderBookCalculator();

    @Test
    void buyWalksMultipleAskLevelsAndCalculatesVwap() {
        ExecutionEstimate result = calculator.estimateBuy(
                List.of(new Level(100, 5), new Level(110, 10)), 1_050);

        assertThat(result.fullyFilled()).isTrue();
        assertThat(result.baseAmount()).isEqualTo(10);
        assertThat(result.averagePrice()).isEqualTo(105);
    }

    @Test
    void sellWalksMultipleBidLevels() {
        ExecutionEstimate result = calculator.estimateSell(
                List.of(new Level(120, 2), new Level(110, 3)), 5);

        assertThat(result.fullyFilled()).isTrue();
        assertThat(result.quoteAmount()).isEqualTo(570);
        assertThat(result.averagePrice()).isEqualTo(114);
    }

    @Test
    void insufficientDepthIsRejected() {
        ExecutionEstimate result = calculator.estimateSell(List.of(new Level(120, 2)), 3);
        assertThat(result.fullyFilled()).isFalse();
    }
}
