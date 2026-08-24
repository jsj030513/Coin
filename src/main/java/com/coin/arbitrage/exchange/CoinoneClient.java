package com.coin.arbitrage.exchange;

import com.coin.arbitrage.config.ArbitrageProperties;
import com.coin.arbitrage.domain.MarketTicker;
import com.coin.arbitrage.domain.OrderBookSnapshot;
import com.coin.arbitrage.domain.OrderBookSnapshot.Level;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CoinoneClient extends AbstractPublicClient {
    private static final String BASE = "https://api.coinone.co.kr/public/v2";

    public CoinoneClient(HttpClient http, ObjectMapper mapper, ArbitrageProperties properties) {
        super(http, mapper, properties);
    }

    @Override
    public String id() {
        return "coinone";
    }

    @Override
    public Set<String> fetchKrwMarkets() {
        JsonNode root = get(BASE + "/markets/KRW");
        Set<String> result = new HashSet<>();
        root.path("markets").forEach(row -> {
            String target = row.path("target_currency").asText().toUpperCase();
            if (!target.isBlank() && !"KRW".equals(target)) result.add(target + "/KRW");
        });
        return result;
    }

    @Override
    public Map<String, MarketTicker> fetchTickers(Set<String> symbols) {
        Map<String, MarketTicker> result = new HashMap<>();
        JsonNode root = get(BASE + "/ticker_new/KRW");
        root.path("tickers").forEach(row -> {
            String symbol = row.path("target_currency").asText().toUpperCase() + "/KRW";
            if (symbols.contains(symbol)) {
                double last = number(row, "last");
                result.put(symbol, new MarketTicker(id(), symbol,
                        last, last, last, number(row, "target_volume"), number(row, "quote_volume")));
            }
        });
        return result;
    }

    @Override
    public OrderBookSnapshot fetchOrderBook(String symbol) {
        String asset = symbol.substring(0, symbol.indexOf('/'));
        JsonNode root = get(BASE + "/orderbook/KRW/" + asset + "?size=30");
        List<Level> bids = levels(root.path("bids"));
        List<Level> asks = levels(root.path("asks"));
        return new OrderBookSnapshot(id(), symbol, bids, asks);
    }

    private static List<Level> levels(JsonNode rows) {
        List<Level> result = new ArrayList<>();
        rows.forEach(row -> result.add(new Level(number(row, "price"), number(row, "qty"))));
        return result;
    }
}
