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
public class KorbitClient extends AbstractPublicClient {
    private static final String BASE = "https://api.korbit.co.kr/v2";

    public KorbitClient(HttpClient http, ObjectMapper mapper, ArbitrageProperties properties) {
        super(http, mapper, properties);
    }

    @Override
    public String id() {
        return "korbit";
    }

    @Override
    public Set<String> fetchKrwMarkets() {
        Set<String> result = new HashSet<>();
        data(get(BASE + "/tickers")).forEach(row -> {
            String market = row.path("symbol").asText();
            if (market.endsWith("_krw")) result.add(toSymbol(market));
        });
        return result;
    }

    @Override
    public Map<String, MarketTicker> fetchTickers(Set<String> symbols) {
        Map<String, MarketTicker> result = new HashMap<>();
        data(get(BASE + "/tickers")).forEach(row -> {
            String symbol = toSymbol(row.path("symbol").asText());
            if (symbols.contains(symbol)) {
                result.put(symbol, new MarketTicker(id(), symbol,
                        number(row, "bestBidPrice"), number(row, "bestAskPrice"),
                        number(row, "close"), number(row, "volume"), number(row, "quoteVolume")));
            }
        });
        return result;
    }

    @Override
    public OrderBookSnapshot fetchOrderBook(String symbol) {
        JsonNode book = data(get(BASE + "/orderbook?symbol=" + toMarket(symbol)));
        List<Level> bids = levels(book.path("bids"));
        List<Level> asks = levels(book.path("asks"));
        return new OrderBookSnapshot(id(), symbol, bids, asks);
    }

    private static JsonNode data(JsonNode node) {
        return node.has("data") ? node.path("data") : node;
    }

    private static List<Level> levels(JsonNode rows) {
        List<Level> result = new ArrayList<>();
        rows.forEach(row -> result.add(new Level(number(row, "price"), number(row, "qty"))));
        return result;
    }

    private static String toSymbol(String market) {
        return market.replace("_", "/").toUpperCase();
    }

    private static String toMarket(String symbol) {
        return symbol.replace("/", "_").toLowerCase();
    }
}
