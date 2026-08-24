package com.coin.arbitrage.web;

import com.coin.arbitrage.persistence.OpportunityEntity;
import com.coin.arbitrage.persistence.OpportunityRepository;
import com.coin.arbitrage.service.LiveBalanceService;
import java.security.Principal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {
    private final OpportunityRepository opportunities;
    private final LiveBalanceService liveBalances;

    public PortfolioController(OpportunityRepository opportunities, LiveBalanceService liveBalances) {
        this.opportunities = opportunities;
        this.liveBalances = liveBalances;
    }

    @GetMapping
    public PortfolioResponse portfolio(Principal principal) {
        List<PortfolioBalance> balances = liveBalances.snapshot(principal.getName()).balances().stream()
                .map(value -> new PortfolioBalance(value.exchange() + ":" + value.asset(),
                        value.total().doubleValue(), value.avgBuyPrice().doubleValue()))
                .sorted(Comparator.comparing(PortfolioBalance::currency))
                .toList();
        double krw = balances.stream().filter(value -> value.currency().endsWith(":KRW"))
                .mapToDouble(PortfolioBalance::amount).sum();
        double assets = estimateAssetValue(balances);
        return new PortfolioResponse(new PortfolioSummary(krw, assets, krw + assets), balances,
                List.of("UPBIT", "BITHUMB"));
    }

    private double estimateAssetValue(List<PortfolioBalance> balances) {
        Map<String, Double> prices = recentPrices();
        return balances.stream().filter(value -> !value.currency().endsWith(":KRW"))
                .mapToDouble(value -> value.amount()
                        * prices.getOrDefault(value.currency().toUpperCase(), value.avgBuyPriceKrw()))
                .sum();
    }

    private Map<String, Double> recentPrices() {
        Map<String, Double> prices = new HashMap<>();
        for (OpportunityEntity opportunity : opportunities.findTop1000ByOrderByDetectedAtDesc()) {
            String asset = opportunity.getSymbol().substring(0, opportunity.getSymbol().indexOf('/')).toUpperCase();
            prices.putIfAbsent(key(opportunity.getBuyExchange(), asset), opportunity.getBuyPrice());
            prices.putIfAbsent(key(opportunity.getSellExchange(), asset), opportunity.getSellPrice());
        }
        return prices;
    }

    private static String key(String exchange, String asset) {
        return exchange.trim().toUpperCase() + ":" + asset.trim().toUpperCase();
    }

    public record PortfolioResponse(PortfolioSummary summary, List<PortfolioBalance> balances,
                                    List<String> enabledExchanges) { }
    public record PortfolioBalance(String currency, double amount, double avgBuyPriceKrw) { }
    public record PortfolioSummary(double currentKrwBalance, double currentAssetValueKrw,
                                   double currentPortfolioValueKrw) { }
}
