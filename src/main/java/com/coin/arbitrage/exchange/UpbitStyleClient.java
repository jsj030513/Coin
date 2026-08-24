package com.coin.arbitrage.exchange;

import com.coin.arbitrage.config.ArbitrageProperties;
import com.coin.arbitrage.domain.MarketTicker;
import com.coin.arbitrage.domain.OrderBookSnapshot;
import com.coin.arbitrage.domain.OrderBookSnapshot.Level;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

abstract class UpbitStyleClient extends AbstractPublicClient {
    private final String exchangeId;
    private final String baseUrl;

    protected UpbitStyleClient(String exchangeId, String baseUrl, HttpClient http,
                               ObjectMapper mapper, ArbitrageProperties properties) {
        super(http, mapper, properties);
        this.exchangeId = exchangeId;
        this.baseUrl = baseUrl;
    }

    @Override
    public String id() {
        return exchangeId;
    }

    @Override
    public Set<String> fetchKrwMarkets() {
        JsonNode rows = get(baseUrl + "/v1/market/all?isDetails=false");
        Set<String> symbols = new HashSet<>();
        rows.forEach(row -> {
            String market = row.path("market").asText();
            if (market.startsWith("KRW-")) symbols.add(toSymbol(market));
        });
        return symbols;
    }

    @Override
    public Map<String, MarketTicker> fetchTickers(Set<String> symbols) {
        Map<String, MarketTicker> result = new HashMap<>();
        List<String> values = symbols.stream().map(UpbitStyleClient::toMarket).sorted().toList();
        for (int start = 0; start < values.size(); start += 100) {
            String markets = String.join(",", values.subList(start, Math.min(start + 100, values.size())));
            JsonNode rows = get(baseUrl + "/v1/ticker?markets=" + encode(markets));
            rows.forEach(row -> {
                String symbol = toSymbol(row.path("market").asText());
                result.put(symbol, new MarketTicker(id(), symbol,
                        number(row, "trade_price"), number(row, "trade_price"),
                        number(row, "trade_price"), number(row, "acc_trade_volume_24h"),
                        number(row, "acc_trade_price_24h")));
            });
        }
        // Upbit-style ticker has no best bid/ask; only order books make final decisions.
        return result;
    }

    @Override
    public OrderBookSnapshot fetchOrderBook(String symbol) {
        JsonNode root = get(baseUrl + "/v1/orderbook?markets=" + encode(toMarket(symbol)));
        JsonNode book = root.isArray() ? root.path(0) : root;
        List<Level> bids = new ArrayList<>();
        List<Level> asks = new ArrayList<>();
        book.path("orderbook_units").forEach(unit -> {
            bids.add(new Level(number(unit, "bid_price"), number(unit, "bid_size")));
            asks.add(new Level(number(unit, "ask_price"), number(unit, "ask_size")));
        });
        return new OrderBookSnapshot(id(), symbol, bids, asks);
    }

    private static String toSymbol(String market) {
        String[] parts = market.split("-");
        return parts.length == 2 ? parts[1].toUpperCase() + "/" + parts[0].toUpperCase() : market;
    }

    private static String toMarket(String symbol) {
        String[] parts = symbol.split("/");
        return parts[1].toUpperCase() + "-" + parts[0].toUpperCase();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
