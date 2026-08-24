package com.coin.arbitrage.exchange;

import com.coin.arbitrage.domain.MarketTicker;
import com.coin.arbitrage.domain.OrderBookSnapshot;
import java.util.Map;
import java.util.Set;

public interface ExchangeMarketClient {
    String id();
    Set<String> fetchKrwMarkets();
    Map<String, MarketTicker> fetchTickers(Set<String> symbols);
    OrderBookSnapshot fetchOrderBook(String symbol);
}
