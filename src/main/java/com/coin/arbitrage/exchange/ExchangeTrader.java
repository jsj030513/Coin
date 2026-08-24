package com.coin.arbitrage.exchange;

import com.coin.arbitrage.domain.ExchangeBalance;
import com.coin.arbitrage.domain.OrderResult;
import com.coin.arbitrage.domain.OrderStatus;
import java.math.BigDecimal;

public interface ExchangeTrader {
    default String id() { return "disabled"; }

    default ExchangeBalance getBalances() {
        throw new UnsupportedOperationException("Live trading disabled");
    }

    default OrderResult buyMarket(String symbol, BigDecimal krwAmount) {
        throw new UnsupportedOperationException("Live trading disabled");
    }

    default OrderResult sellMarket(String symbol, BigDecimal quantity) {
        throw new UnsupportedOperationException("Live trading disabled");
    }

    default OrderStatus getOrderStatus(String orderId) {
        throw new UnsupportedOperationException("Live trading disabled");
    }

    default void cancelOrder(String orderId) {
        throw new UnsupportedOperationException("Live trading disabled");
    }
}
