package com.coin.arbitrage.service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.coin.arbitrage.persistence.ExchangeConnectionEntity;
import com.coin.arbitrage.persistence.ExchangeConnectionRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FeeProvider {
    // Immediately executable orders consume the book, so these are taker rates.
    private final Map<String, Double> buyFees = new ConcurrentHashMap<>();
    private final Map<String, Double> sellFees = new ConcurrentHashMap<>();
    private final ExchangeConnectionRepository connections;

    @Autowired
    public FeeProvider(@Value("${fees.upbit:0.05}") double upbit,
                       @Value("${fees.bithumb:0.04}") double bithumb,
                       @Value("${fees.coinone:0.02}") double coinone,
                       @Value("${fees.korbit:0.20}") double korbit,
                       ExchangeConnectionRepository connections) {
        this(upbit, bithumb, coinone, korbit, connections, true);
    }

    FeeProvider(double upbit, double bithumb, double coinone, double korbit) {
        this(upbit, bithumb, coinone, korbit, null, true);
    }

    private FeeProvider(double upbit, double bithumb, double coinone, double korbit,
                        ExchangeConnectionRepository connections, boolean ignored) {
        this.connections = connections;
        buyFees.putAll(Map.of("upbit", valid(upbit), "bithumb", valid(bithumb),
                "coinone", valid(coinone), "korbit", valid(korbit)));
        sellFees.putAll(buyFees);
    }

    @PostConstruct
    void loadVerifiedAccountFees() {
        if (connections == null) return;
        for (ExchangeConnectionEntity.Exchange exchange : ExchangeConnectionEntity.Exchange.values()) {
            connections.findFirstByExchangeAndStatus(exchange, ExchangeConnectionEntity.Status.VERIFIED)
                    .ifPresent(value -> override(exchange.name(), value.getBuyFeePercent(), value.getSellFeePercent()));
        }
    }

    public void override(String exchange, Double buyFeePercent, Double sellFeePercent) {
        String key = exchange.trim().toLowerCase(Locale.ROOT);
        if (buyFeePercent != null) buyFees.put(key, valid(buyFeePercent));
        if (sellFeePercent != null) sellFees.put(key, valid(sellFeePercent));
    }

    public double buyFee(String exchange) { return fee(buyFees, exchange); }
    public double sellFee(String exchange) { return fee(sellFees, exchange); }
    public double buyFee(String username, String exchange) { return accountFee(username, exchange, true); }
    public double sellFee(String username, String exchange) { return accountFee(username, exchange, false); }

    public Map<String, Double> takerFees() { return Map.copyOf(buyFees); }

    public CostBreakdown calculate(String buyExchange, String sellExchange,
                                   double buyQuoteKrw, double sellQuoteKrw) {
        if (!Double.isFinite(buyQuoteKrw) || buyQuoteKrw <= 0
                || !Double.isFinite(sellQuoteKrw) || sellQuoteKrw < 0) {
            throw new IllegalArgumentException("Quote amounts must be finite and non-negative");
        }
        double buyFeeKrw = buyQuoteKrw * buyFee(buyExchange) / 100.0;
        double sellFeeKrw = sellQuoteKrw * sellFee(sellExchange) / 100.0;
        double totalBuyCostKrw = buyQuoteKrw + buyFeeKrw;
        double netSellProceedsKrw = sellQuoteKrw - sellFeeKrw;
        double expectedProfitKrw = netSellProceedsKrw - totalBuyCostKrw;
        double netProfitPercent = expectedProfitKrw / totalBuyCostKrw * 100.0;
        return new CostBreakdown(buyFeeKrw, sellFeeKrw, totalBuyCostKrw,
                netSellProceedsKrw, expectedProfitKrw, netProfitPercent);
    }

    public CostBreakdown calculate(String username, String buyExchange, String sellExchange,
                                   double buyQuoteKrw, double sellQuoteKrw) {
        double buyFeeKrw = buyQuoteKrw * buyFee(username, buyExchange) / 100.0;
        double sellFeeKrw = sellQuoteKrw * sellFee(username, sellExchange) / 100.0;
        double totalBuyCostKrw = buyQuoteKrw + buyFeeKrw;
        double netSellProceedsKrw = sellQuoteKrw - sellFeeKrw;
        double expectedProfitKrw = netSellProceedsKrw - totalBuyCostKrw;
        return new CostBreakdown(buyFeeKrw, sellFeeKrw, totalBuyCostKrw, netSellProceedsKrw,
                expectedProfitKrw, expectedProfitKrw / totalBuyCostKrw * 100.0);
    }

    private double accountFee(String username, String exchange, boolean buy) {
        if (connections != null && username != null) {
            try {
                var type = ExchangeConnectionEntity.Exchange.valueOf(exchange.trim().toUpperCase(Locale.ROOT));
                var row = connections.findByUserUsernameAndExchange(username, type).orElse(null);
                if (row != null && row.getStatus() == ExchangeConnectionEntity.Status.VERIFIED) {
                    Double value = buy ? row.getBuyFeePercent() : row.getSellFeePercent();
                    if (value != null) return valid(value);
                }
            } catch (IllegalArgumentException ignored) { }
        }
        return buy ? buyFee(exchange) : sellFee(exchange);
    }

    private double fee(Map<String, Double> source, String exchange) {
        Double value = source.get(exchange.trim().toLowerCase(Locale.ROOT));
        if (value == null) throw new IllegalArgumentException("Unknown exchange fee: " + exchange);
        return value;
    }

    private static double valid(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 5)
            throw new IllegalArgumentException("Exchange fee must be between 0 and 5 percent");
        return value;
    }

    public record CostBreakdown(double buyFeeKrw, double sellFeeKrw,
                                double totalBuyCostKrw, double netSellProceedsKrw,
                                double expectedProfitKrw, double netProfitPercent) { }
}
