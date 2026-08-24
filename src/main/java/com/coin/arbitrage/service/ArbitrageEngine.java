package com.coin.arbitrage.service;

import com.coin.arbitrage.domain.ArbitrageOpportunity;
import com.coin.arbitrage.config.ArbitrageProperties;
import com.coin.arbitrage.domain.ExecutionEstimate;
import com.coin.arbitrage.domain.MarketTicker;
import com.coin.arbitrage.domain.OrderBookSnapshot;
import com.coin.arbitrage.exchange.ExchangeMarketClient;
import com.coin.arbitrage.persistence.OpportunityEntity;
import com.coin.arbitrage.persistence.OpportunityRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ArbitrageEngine {
    private static final Logger log = LoggerFactory.getLogger(ArbitrageEngine.class);
    private final List<ExchangeMarketClient> clients;
    private final Map<String, ExchangeMarketClient> clientsById;
    private final RiskSettingsService settingsService;
    private final FeeProvider feeProvider;
    private final OrderBookCalculator calculator;
    private final OpportunityRepository opportunities;
    private final TelegramNotificationService telegram;
    private final Executor marketExecutor;
    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile Set<String> commonSymbols = Set.of();
    private volatile Map<String, Set<String>> symbolsByExchange = Map.of();
    private volatile ScanStatus status = new ScanStatus(false, 0, null, null, null);

    public ArbitrageEngine(List<ExchangeMarketClient> clients, RiskSettingsService settingsService,
                           ArbitrageProperties properties,
                           FeeProvider feeProvider,
                           OrderBookCalculator calculator, OpportunityRepository opportunities,
                           TelegramNotificationService telegram,
                           Executor marketExecutor) {
        Set<String> enabled = properties.enabledExchanges().stream()
                .map(String::toLowerCase).collect(java.util.stream.Collectors.toSet());
        this.clients = clients.stream().filter(client -> enabled.contains(client.id().toLowerCase())).toList();
        this.settingsService = settingsService;
        this.feeProvider = feeProvider;
        this.calculator = calculator;
        this.opportunities = opportunities;
        this.telegram = telegram;
        this.marketExecutor = marketExecutor;
        this.clientsById = new HashMap<>();
        this.clients.forEach(client -> clientsById.put(client.id(), client));
    }

    public List<ArbitrageOpportunity> scan() {
        if (!scanning.compareAndSet(false, true)) return List.of();
        Instant started = Instant.now();
        status = new ScanStatus(true, commonSymbols.size(), started, status.lastCompletedAt(), null);
        try {
            if (commonSymbols.isEmpty()) discoverCommonSymbols();
            Map<String, Map<String, MarketTicker>> tickers = fetchAllTickers();
            RiskSettingsService.Settings settings = settingsService.scanSettings();
            List<Candidate> candidates = findCandidates(tickers, settings);
            Map<BookKey, OrderBookSnapshot> books = fetchBooks(candidates);
            List<ArbitrageOpportunity> found = calculate(candidates, tickers, books, settings);
            opportunities.saveAll(found.stream().map(OpportunityEntity::new).toList());
            telegram.notifyOpportunities(found);
            status = new ScanStatus(false, commonSymbols.size(), started, Instant.now(), null);
            return found;
        } catch (Exception error) {
            log.error("Arbitrage Scan Failed", error);
            status = new ScanStatus(false, commonSymbols.size(), started, Instant.now(), error.getMessage());
            return List.of();
        } finally {
            scanning.set(false);
        }
    }

    public synchronized Set<String> discoverCommonSymbols() {
        List<CompletableFuture<Set<String>>> futures = clients.stream()
                .map(client -> CompletableFuture.supplyAsync(client::fetchKrwMarkets, marketExecutor))
                .toList();
        try {
            List<Set<String>> markets = futures.stream().map(CompletableFuture::join).toList();
            Map<String, Set<String>> discoveredByExchange = new HashMap<>();
            for (int i = 0; i < clients.size(); i++) {
                discoveredByExchange.put(clients.get(i).id(), Set.copyOf(markets.get(i)));
            }
            Map<String, Integer> listingCounts = new HashMap<>();
            markets.forEach(exchangeMarkets -> exchangeMarkets.forEach(symbol ->
                    listingCounts.merge(symbol, 1, Integer::sum)));
            Set<String> pairwiseCommon = new HashSet<>();
            listingCounts.forEach((symbol, exchangeCount) -> {
                if (exchangeCount >= 2) pairwiseCommon.add(symbol);
            });
            symbolsByExchange = Map.copyOf(discoveredByExchange);
            commonSymbols = Set.copyOf(pairwiseCommon);
            log.info("Pairwise Common KRW Markets Discovered | count={} exchanges={}",
                    commonSymbols.size(), clients.size());
        } catch (Exception error) {
            log.error("Market Discovery Failed; keeping previous symbols", error);
        }
        return commonSymbols;
    }

    private Map<String, Map<String, MarketTicker>> fetchAllTickers() {
        List<CompletableFuture<Map<String, MarketTicker>>> futures = clients.stream()
                .map(client -> CompletableFuture.supplyAsync(() -> {
                    Set<String> supported = new HashSet<>(symbolsByExchange.getOrDefault(client.id(), Set.of()));
                    supported.retainAll(commonSymbols);
                    return client.fetchTickers(supported);
                }, marketExecutor))
                .toList();
        Map<String, Map<String, MarketTicker>> result = new HashMap<>();
        for (int i = 0; i < clients.size(); i++) {
            ExchangeMarketClient client = clients.get(i);
            try {
                Map<String, MarketTicker> rows = futures.get(i).join();
                rows.forEach((symbol, ticker) -> result.computeIfAbsent(symbol, ignored -> new HashMap<>())
                        .put(client.id(), ticker));
            } catch (Exception error) {
                log.error("Ticker collection failed | exchange={}", client.id(), error);
            }
        }
        return result;
    }

    private List<Candidate> findCandidates(Map<String, Map<String, MarketTicker>> tickers,
                                           RiskSettingsService.Settings settings) {
        List<Candidate> result = new ArrayList<>();
        tickers.forEach((symbol, rows) -> rows.forEach((buyId, buy) -> rows.forEach((sellId, sell) -> {
            if (buyId.equals(sellId) || !buy.valid() || !sell.valid()) return;
            if (Math.min(buy.quoteVolume(), sell.quoteVolume()) < settings.minQuoteVolume24h()) return;
            double rough = (sell.bid() - buy.ask()) / buy.ask() * 100
                    - feeProvider.buyFee(buyId) - feeProvider.sellFee(sellId);
            if (rough >= settings.minProfitPercent()) result.add(new Candidate(symbol, buyId, sellId));
        })));
        return result;
    }

    private Map<BookKey, OrderBookSnapshot> fetchBooks(List<Candidate> candidates) {
        Set<BookKey> keys = new HashSet<>();
        candidates.forEach(value -> {
            keys.add(new BookKey(value.symbol(), value.buyExchange()));
            keys.add(new BookKey(value.symbol(), value.sellExchange()));
        });
        Map<String, List<BookKey>> keysByExchange = new HashMap<>();
        keys.forEach(key -> keysByExchange.computeIfAbsent(key.exchange(), ignored -> new ArrayList<>()).add(key));
        List<CompletableFuture<Map<BookKey, OrderBookSnapshot>>> futures = keysByExchange.entrySet().stream()
                .map(entry -> CompletableFuture.supplyAsync(() -> fetchBooksSequentially(entry.getValue()), marketExecutor))
                .toList();
        Map<BookKey, OrderBookSnapshot> result = new HashMap<>();
        futures.forEach(future -> result.putAll(future.join()));
        return result;
    }

    private Map<BookKey, OrderBookSnapshot> fetchBooksSequentially(List<BookKey> keys) {
        Map<BookKey, OrderBookSnapshot> result = new HashMap<>();
        keys.sort(Comparator.comparing(BookKey::symbol));
        for (BookKey key : keys) {
            try {
                result.put(key, clientsById.get(key.exchange()).fetchOrderBook(key.symbol()));
                Thread.sleep(250);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception error) {
                log.error("Orderbook collection failed | {} {}", key.exchange(), key.symbol());
            }
        }
        return result;
    }

    private List<ArbitrageOpportunity> calculate(List<Candidate> candidates,
                                                  Map<String, Map<String, MarketTicker>> tickers,
                                                  Map<BookKey, OrderBookSnapshot> books,
                                                  RiskSettingsService.Settings settings) {
        List<ArbitrageOpportunity> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            OrderBookSnapshot buyBook = books.get(new BookKey(candidate.symbol(), candidate.buyExchange()));
            OrderBookSnapshot sellBook = books.get(new BookKey(candidate.symbol(), candidate.sellExchange()));
            if (buyBook == null || sellBook == null) continue;
            ExecutionEstimate buy = calculator.estimateBuy(buyBook.asks(), settings.orderAmountKrw());
            if (!buy.fullyFilled() || buy.averagePrice() <= 0) continue;
            ExecutionEstimate sell = calculator.estimateSell(sellBook.bids(), buy.baseAmount());
            if (!sell.fullyFilled() || sell.averagePrice() <= 0) continue;
            double raw = (sell.averagePrice() - buy.averagePrice()) / buy.averagePrice() * 100;
            FeeProvider.CostBreakdown costs = feeProvider.calculate(candidate.buyExchange(),
                    candidate.sellExchange(), buy.quoteAmount(), sell.quoteAmount());
            double expectedProfit = costs.expectedProfitKrw();
            double net = costs.netProfitPercent();
            if (net < settings.minProfitPercent() || net > settings.maxProfitPercent()) continue;
            if (expectedProfit < settings.minExpectedProfitKrw()) continue;
            MarketTicker buyTicker = tickers.get(candidate.symbol()).get(candidate.buyExchange());
            MarketTicker sellTicker = tickers.get(candidate.symbol()).get(candidate.sellExchange());
            ArbitrageOpportunity opportunity = new ArbitrageOpportunity(candidate.symbol(),
                    candidate.buyExchange(), candidate.sellExchange(), buy.averagePrice(), sell.averagePrice(),
                    buy.baseAmount(), buy.quoteAmount(), raw, net,
                    expectedProfit,
                    buyTicker.quoteVolume(), sellTicker.quoteVolume(), Instant.now());
            result.add(opportunity);
            log.info("Arbitrage Opportunity Found | symbol={} buy={} sell={} net={}%%",
                    opportunity.symbol(), opportunity.buyExchange(), opportunity.sellExchange(), opportunity.netProfitPercent());
        }
        return result.stream().sorted(Comparator.comparingDouble(ArbitrageOpportunity::netProfitPercent).reversed()).toList();
    }

    public ScanStatus status() { return status; }
    public Set<String> commonSymbols() { return commonSymbols; }

    public BigDecimal currentBid(String symbol, String exchange) {
        ExchangeMarketClient client = clientsById.get(exchange.toLowerCase());
        if (client == null) return BigDecimal.ZERO;
        try {
            MarketTicker ticker = client.fetchTickers(Set.of(symbol)).get(symbol);
            return ticker == null || !ticker.valid() ? BigDecimal.ZERO : BigDecimal.valueOf(ticker.bid());
        } catch (Exception error) {
            log.warn("Current bid lookup failed | exchange={} symbol={}", exchange, symbol);
            return BigDecimal.ZERO;
        }
    }

    public SellEstimate estimateSell(String symbol, String exchange, BigDecimal quantity) {
        ExchangeMarketClient client = clientsById.get(exchange.toLowerCase());
        if (client == null || quantity == null || quantity.signum() <= 0) return SellEstimate.blocked();
        try {
            ExecutionEstimate estimate = calculator.estimateSell(
                    client.fetchOrderBook(symbol).bids(), quantity.doubleValue());
            return new SellEstimate(estimate.fullyFilled(), BigDecimal.valueOf(estimate.baseAmount()),
                    BigDecimal.valueOf(estimate.quoteAmount()), BigDecimal.valueOf(estimate.averagePrice()));
        } catch (Exception error) {
            return SellEstimate.blocked();
        }
    }

    public RevalidatedOpportunity revalidate(String symbol, String buyExchange,
                                              String sellExchange, BigDecimal amountKrw) {
        return revalidate(null, symbol, buyExchange, sellExchange, amountKrw);
    }
    public RevalidatedOpportunity revalidate(String username, String symbol, String buyExchange,
                                              String sellExchange, BigDecimal amountKrw) {
        String buyId = buyExchange.toLowerCase();
        String sellId = sellExchange.toLowerCase();
        ExchangeMarketClient buyClient = clientsById.get(buyId);
        ExchangeMarketClient sellClient = clientsById.get(sellId);
        if (buyClient == null || sellClient == null || amountKrw == null || amountKrw.signum() <= 0) {
            return RevalidatedOpportunity.blocked("지원하지 않는 거래소 또는 주문금액");
        }
        try {
            CompletableFuture<OrderBookSnapshot> buyFuture = CompletableFuture.supplyAsync(
                    () -> buyClient.fetchOrderBook(symbol), marketExecutor);
            CompletableFuture<OrderBookSnapshot> sellFuture = CompletableFuture.supplyAsync(
                    () -> sellClient.fetchOrderBook(symbol), marketExecutor);
            ExecutionEstimate buy = calculator.estimateBuy(buyFuture.join().asks(), amountKrw.doubleValue());
            if (!buy.fullyFilled() || buy.baseAmount() <= 0) return RevalidatedOpportunity.blocked("매수 호가 부족");
            ExecutionEstimate sell = calculator.estimateSell(sellFuture.join().bids(), buy.baseAmount());
            if (!sell.fullyFilled() || sell.baseAmount() <= 0) return RevalidatedOpportunity.blocked("매도 호가 부족");
            FeeProvider.CostBreakdown costs = username == null
                    ? feeProvider.calculate(buyId, sellId, buy.quoteAmount(), sell.quoteAmount())
                    : feeProvider.calculate(username, buyId, sellId, buy.quoteAmount(), sell.quoteAmount());
            return new RevalidatedOpportunity(true, BigDecimal.valueOf(buy.baseAmount()),
                    BigDecimal.valueOf(sell.quoteAmount()), costs.expectedProfitKrw(),
                    costs.netProfitPercent(), null);
        } catch (Exception error) {
            return RevalidatedOpportunity.blocked("주문 직전 호가 재검증 실패");
        }
    }

    private record Candidate(String symbol, String buyExchange, String sellExchange) { }
    private record BookKey(String symbol, String exchange) { }
    public record ScanStatus(boolean scanning, int commonSymbolCount, Instant lastStartedAt,
                             Instant lastCompletedAt, String lastError) { }
    public record RevalidatedOpportunity(boolean executable, BigDecimal sellQuantity,
                                         BigDecimal sellQuoteAmount,
                                         double expectedProfitKrw, double netProfitPercent,
                                         String reason) {
        static RevalidatedOpportunity blocked(String reason) {
            return new RevalidatedOpportunity(false, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, reason);
        }
    }
    public record SellEstimate(boolean executable, BigDecimal quantity, BigDecimal quoteAmount,
                               BigDecimal averagePrice) {
        static SellEstimate blocked() {
            return new SellEstimate(false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }
}
