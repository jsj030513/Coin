package com.coin.arbitrage.exchange;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ExchangeTraderSafetyTest {
    private final ExchangeTrader trader = new ExchangeTrader() { };

    @Test
    void everyLiveOperationIsBlocked() {
        assertThatThrownBy(() -> trader.buyMarket("BTC/KRW", BigDecimal.ONE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> trader.sellMarket("BTC/KRW", BigDecimal.ONE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(trader::getBalances)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> trader.getOrderStatus("blocked"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> trader.cancelOrder("blocked"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
