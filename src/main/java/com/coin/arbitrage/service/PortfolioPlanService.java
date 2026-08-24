package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.ExchangeConnectionEntity;
import com.coin.arbitrage.persistence.ExchangeConnectionEntity.Exchange;
import com.coin.arbitrage.persistence.ExchangeConnectionRepository;
import com.coin.arbitrage.persistence.LiveOrderRepository;
import com.coin.arbitrage.persistence.OpportunityRepository;
import com.coin.arbitrage.persistence.PortfolioPlanSettingsEntity;
import com.coin.arbitrage.persistence.PortfolioPlanSettingsRepository;
import com.coin.arbitrage.persistence.UserAccountEntity;
import com.coin.arbitrage.persistence.UserAccountRepository;
import com.coin.arbitrage.persistence.TradeCycleRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class PortfolioPlanService {
    private static final long MIN_SEED_BUY_KRW = 5_000;
    private static final String SEED_SOURCE = "MANUAL_SEED_BUY";

    private final OpportunityRepository opportunities;
    private final LiveBalanceService liveBalances;
    private final ExchangeConnectionRepository connections;
    private final LiveExchangeOrderService orders;
    private final LiveOrderHistoryService orderHistory;
    private final PortfolioPlanSettingsRepository planSettings;
    private final UserAccountRepository users;
    private final LiveOrderRepository liveOrders;
    private final ArbitrageEngine engine;
    private final TradingSettingsService trading;
    private final TelegramNotificationService telegram;
    private final TradeCycleRepository tradeCycles;
    private final RiskSettingsService riskSettings;

    public PortfolioPlanService(OpportunityRepository opportunities, LiveBalanceService liveBalances,
                                ExchangeConnectionRepository connections, LiveExchangeOrderService orders,
                                LiveOrderHistoryService orderHistory,
                                PortfolioPlanSettingsRepository planSettings,
                                UserAccountRepository users,
                                LiveOrderRepository liveOrders, ArbitrageEngine engine,
                                TradingSettingsService trading, TelegramNotificationService telegram,
                                TradeCycleRepository tradeCycles, RiskSettingsService riskSettings) {
        this.opportunities = opportunities;
        this.liveBalances = liveBalances;
        this.connections = connections;
        this.orders = orders;
        this.orderHistory = orderHistory;
        this.planSettings = planSettings;
        this.users = users;
        this.liveOrders = liveOrders;
        this.engine = engine;
        this.trading = trading;
        this.telegram = telegram;
        this.tradeCycles = tradeCycles;
        this.riskSettings = riskSettings;
    }

    public PortfolioPlan plan(String username) {
        Settings settings = settings(username);
        Instant since = Instant.now().minus(Duration.ofHours(1));
        List<String> profitableSymbols = tradeCycles.findProfitableSymbolsSince(username, since.minus(Duration.ofDays(6)));
        RiskSettingsService.Settings risk = riskSettings.get(username);
        List<OpportunityRepository.OpportunityPerformance> top = opportunities
                .summarizeSince(since, risk.minProfitPercent(), risk.maxProfitPercent()).stream()
                .sorted(java.util.Comparator.comparingInt(value -> {
                    int rank = profitableSymbols.indexOf(value.getSymbol());
                    return rank < 0 ? Integer.MAX_VALUE : rank;
                }))
                .limit(settings.targetSymbolCount())
                .toList();
        LiveBalanceService.LiveBalanceResponse snapshot = liveBalances.snapshot(username);
        List<PlanSymbol> symbols = top.stream()
                .map(value -> planSymbol(value, snapshot, settings))
                .toList();
        long upbitCash = krw(snapshot, "UPBIT");
        long bithumbCash = krw(snapshot, "BITHUMB");
        return new PortfolioPlan(since, settings, upbitCash, bithumbCash, symbols);
    }


    public SeedBuyDecision approveSeedBuy(String username, SeedBuyRequest request) {
        return processSeedBuy(username, request, true);
    }

    public PairSeedBuyDecision approvePairSeedBuy(String username, PairSeedBuyRequest request) {
        BigDecimal amount = sanitize(request.krwAmount());
        SeedBuyRequest upbitRequest = new SeedBuyRequest(request.symbol(), "UPBIT", amount);
        SeedBuyRequest bithumbRequest = new SeedBuyRequest(request.symbol(), "BITHUMB", amount);
        SeedBuyDecision upbitCheck = processSeedBuy(username, upbitRequest, false);
        if (!upbitCheck.accepted()) return new PairSeedBuyDecision(false, upbitCheck.message(), request.symbol(), amount, Instant.now());
        SeedBuyDecision bithumbCheck = processSeedBuy(username, bithumbRequest, false);
        if (!bithumbCheck.accepted()) return new PairSeedBuyDecision(false, bithumbCheck.message(), request.symbol(), amount, Instant.now());
        try {
            orderHistory.record(username, "BUY", amount, SEED_SOURCE,
                    orders.buySeedMarket(username, "UPBIT", normalizeSymbol(request.symbol()), amount));
            orderHistory.record(username, "BUY", amount, SEED_SOURCE,
                    orders.buySeedMarket(username, "BITHUMB", normalizeSymbol(request.symbol()), amount));
            return new PairSeedBuyDecision(true, "업비트와 빗썸에 동일 금액 매수 주문을 전송했습니다.",
                    normalizeSymbol(request.symbol()), amount, Instant.now());
        } catch (RuntimeException error) {
            trading.emergencyStop(username);
            telegram.notifyAutoTradingFailure(username, normalizeSymbol(request.symbol()),
                    "UPBIT", "BITHUMB", "양 거래소 초기매수 중 일부 주문 실패: " + error.getMessage());
            return new PairSeedBuyDecision(false, "동일 금액 매수 중 일부 주문이 실패했습니다. 거래소 주문 내역을 확인하세요: "
                    + error.getMessage(), normalizeSymbol(request.symbol()), amount, Instant.now());
        }
    }

    private SeedBuyDecision processSeedBuy(String username, SeedBuyRequest request, boolean execute) {
        Settings settings = settings(username);
        String symbol = normalizeSymbol(request.symbol());
        String exchangeName = normalizeExchange(request.exchange());
        BigDecimal amount = sanitize(request.krwAmount());
        if (amount.compareTo(BigDecimal.valueOf(MIN_SEED_BUY_KRW)) < 0) {
            return new SeedBuyDecision(false, "거래소 최소 주문을 고려해 5,000원 이상만 허용합니다.", symbol, exchangeName, amount, Instant.now());
        }
        if (amount.compareTo(BigDecimal.valueOf(settings.maxSeedBuyKrw())) > 0) {
            return new SeedBuyDecision(false, "요청 금액이 1회 최대 초기매수 금액보다 큽니다.", symbol, exchangeName, amount, Instant.now());
        }
        if (amount.compareTo(BigDecimal.valueOf(orders.maxOrderKrw())) > 0) {
            return new SeedBuyDecision(false, "요청 금액이 서버의 실제 주문 상한보다 큽니다.",
                    symbol, exchangeName, amount, Instant.now());
        }

        PortfolioPlan plan = plan(username);
        PlanSymbol target = plan.symbols().stream()
                .filter(value -> value.symbol().equalsIgnoreCase(symbol))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return new SeedBuyDecision(false, "현재 추천 목록에 없는 코인입니다. 추천 새로고침 후 다시 확인하세요.",
                    symbol, exchangeName, amount, Instant.now());
        }

        Holding holding = "UPBIT".equals(exchangeName) ? target.upbit() : target.bithumb();
        if (holding.estimatedValueKrw() >= settings.targetKrwPerSymbolPerExchange()) {
            return new SeedBuyDecision(false, exchangeName + "에 이미 목표 금액 이상 보유 중입니다.",
                    symbol, exchangeName, amount, Instant.now());
        }
        long suggested = "UPBIT".equals(exchangeName) ? target.upbitSuggestedBuyKrw() : target.bithumbSuggestedBuyKrw();
        if (amount.longValue() > suggested) {
            return new SeedBuyDecision(false, "요청 금액이 현재 부족분 기준 권장 매수금액보다 큽니다.",
                    symbol, exchangeName, amount, Instant.now());
        }

        long cash = "UPBIT".equals(exchangeName) ? plan.upbitCashKrw() : plan.bithumbCashKrw();
        if (cash - amount.longValue() < settings.cashReserveKrwPerExchange()) {
            return new SeedBuyDecision(false, "매수 후 설정한 현금 보유액을 남길 수 없어 차단했습니다.",
                    symbol, exchangeName, amount, Instant.now());
        }
        if (recentSeedBuyExists(username, exchangeName, symbol, settings.seedBuyCooldownSeconds())) {
            return new SeedBuyDecision(false, "같은 거래소/코인 초기매수 쿨다운 중입니다. 연속 클릭 방지를 위해 잠시 후 다시 시도하세요.",
                    symbol, exchangeName, amount, Instant.now());
        }

        PermissionState permission = permission(username, exchangeName);
        if (!permission.orderReady()) {
            return new SeedBuyDecision(false, "해당 거래소 API의 주문조회 권한이 확인되지 않았거나 주문하기 권한이 꺼져 있습니다.",
                    symbol, exchangeName, amount, Instant.now());
        }

        if (!orders.seedBuyEnabled()) {
            return new SeedBuyDecision(false,
                    "초기 매수 테스트 안전장치가 잠겨 있어 주문을 보내지 않았습니다. LIVE_SEED_BUY_ENABLED=true가 필요합니다.",
                    symbol, exchangeName, amount, Instant.now());
        }

        if (!execute) {
            return new SeedBuyDecision(true, "주문 전 검증 완료", symbol, exchangeName, amount, Instant.now());
        }

        try {
            orderHistory.record(username, "BUY", amount, SEED_SOURCE,
                    orders.buySeedMarket(username, exchangeName, symbol, amount));
            return new SeedBuyDecision(true, exchangeName + " " + symbol + " " + amount.toPlainString() + "원 초기 매수 주문을 전송했습니다.",
                    symbol, exchangeName, amount, Instant.now());
        } catch (RuntimeException error) {
            return new SeedBuyDecision(false, "초기 매수 주문 실패: " + error.getMessage(),
                    symbol, exchangeName, amount, Instant.now());
        }
    }

    public Settings settings(String username) {
        return toSettings(settingsEntity(username));
    }

    public java.util.Set<String> selectedSymbols(String username) {
        return plan(username).symbols().stream().map(PlanSymbol::symbol)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean initialSeedCompleted(String username, String exchange, String symbol) {
        return liveOrders.existsByUserUsernameAndExchangeAndSymbolAndSource(
                username, normalizeExchange(exchange), normalizeSymbol(symbol), SEED_SOURCE);
    }

    public Settings updateSettings(String username, SettingsRequest request) {
        Settings normalized = validate(request);
        PortfolioPlanSettingsEntity entity = settingsEntity(username);
        entity.update(normalized.targetSymbolCount(), normalized.targetKrwPerSymbolPerExchange(),
                normalized.cashReserveKrwPerExchange(), normalized.maxSeedBuyKrw(),
                normalized.seedBuyCooldownSeconds());
        return toSettings(planSettings.saveAndFlush(entity));
    }

    private PlanSymbol planSymbol(OpportunityRepository.OpportunityPerformance value,
                                  LiveBalanceService.LiveBalanceResponse snapshot,
                                  Settings settings) {
        String asset = value.getSymbol().replace("/KRW", "");
        Holding upbit = holding(snapshot, "UPBIT", value.getSymbol(), asset);
        Holding bithumb = holding(snapshot, "BITHUMB", value.getSymbol(), asset);
        long readyFloor = Math.max(0, settings.targetKrwPerSymbolPerExchange()
                - Math.max(100, settings.targetKrwPerSymbolPerExchange() / 50));
        boolean upbitReady = upbit.estimatedValueKrw() >= readyFloor;
        boolean bithumbReady = bithumb.estimatedValueKrw() >= readyFloor;
        boolean ready = upbitReady && bithumbReady;
        long upbitNeed = upbitReady ? 0 : Math.max(0,
                settings.targetKrwPerSymbolPerExchange() - upbit.estimatedValueKrw());
        long bithumbNeed = bithumbReady ? 0 : Math.max(0,
                settings.targetKrwPerSymbolPerExchange() - bithumb.estimatedValueKrw());
        long actualLimit = Math.min(settings.maxSeedBuyKrw(), orders.maxOrderKrw());
        long upbitSuggested = suggestedBuy(upbitNeed, actualLimit);
        long bithumbSuggested = suggestedBuy(bithumbNeed, actualLimit);
        return new PlanSymbol(value.getSymbol(), value.getOccurrenceCount(),
                value.getTotalExpectedProfitKrw(), value.getAverageProfitPercent(), value.getLastDetectedAt(),
                upbit, bithumb, upbitNeed, bithumbNeed, upbitSuggested, bithumbSuggested, ready);
    }

    private Holding holding(LiveBalanceService.LiveBalanceResponse snapshot, String exchange,
                            String symbol, String asset) {
        return snapshot.balances().stream()
                .filter(value -> exchange.equals(value.exchange()))
                .filter(value -> asset.equals(value.asset()))
                .findFirst()
                .map(value -> {
                    BigDecimal currentBid = engine.currentBid(symbol, exchange);
                    long estimated = value.total().multiply(currentBid)
                            .setScale(0, RoundingMode.DOWN).longValue();
                    return new Holding(value.total(), currentBid, estimated);
                })
                .orElse(new Holding(BigDecimal.ZERO, BigDecimal.ZERO, 0));
    }

    private long krw(LiveBalanceService.LiveBalanceResponse snapshot, String exchange) {
        return snapshot.balances().stream()
                .filter(value -> exchange.equals(value.exchange()))
                .filter(value -> "KRW".equals(value.asset()))
                .map(LiveBalanceService.LiveAssetBalance::free)
                .findFirst()
                .orElse(BigDecimal.ZERO)
                .setScale(0, RoundingMode.DOWN)
                .longValue();
    }

    private PortfolioPlanSettingsEntity settingsEntity(String username) {
        PortfolioPlanSettingsEntity entity = planSettings.findByUserUsername(username).orElseGet(() -> {
            UserAccountEntity user = users.findByUsername(username)
                    .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다."));
            return planSettings.save(new PortfolioPlanSettingsEntity(user));
        });
        if (entity.getStrategyVersion() < 5) {
            entity.update(5, 12_000, 30_000, 12_000, 600);
            entity.markStrategyVersion(5);
            entity = planSettings.save(entity);
        }
        if ("onlymine".equals(username) && entity.getStrategyVersion() < 6) {
            entity.update(entity.getTargetSymbolCount(), entity.getTargetKrwPerSymbolPerExchange(),
                    5_000, entity.getMaxSeedBuyKrw(), entity.getSeedBuyCooldownSeconds());
            entity.markStrategyVersion(6);
            entity = planSettings.save(entity);
        }
        return entity;
    }

    private boolean recentSeedBuyExists(String username, String exchange, String symbol, long cooldownSeconds) {
        if (cooldownSeconds <= 0) return false;
        return liveOrders.existsByUserUsernameAndExchangeAndSymbolAndSourceAndCreatedAtAfter(
                username, exchange, symbol, SEED_SOURCE, Instant.now().minusSeconds(cooldownSeconds));
    }

    private static long suggestedBuy(long needKrw, long maxSeedBuyKrw) {
        if (needKrw <= 0) return 0;
        return Math.max(MIN_SEED_BUY_KRW, Math.min(needKrw, maxSeedBuyKrw));
    }

    private PermissionState permission(String username, String exchangeName) {
        try {
            return connections.findByUserUsernameAndExchange(username, Exchange.valueOf(exchangeName))
                    .filter(value -> value.getStatus() == ExchangeConnectionEntity.Status.VERIFIED)
                    .map(value -> new PermissionState(value.getOrderReadPermission(), value.getOrderCreatePermission()))
                    .orElse(new PermissionState("UNKNOWN", "UNKNOWN"));
        } catch (IllegalArgumentException error) {
            return new PermissionState("UNKNOWN", "UNKNOWN");
        }
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("코인 심볼이 필요합니다.");
        String value = symbol.trim().toUpperCase(Locale.ROOT);
        return value.contains("/") ? value : value + "/KRW";
    }

    private static String normalizeExchange(String exchange) {
        if (exchange == null || exchange.isBlank()) throw new IllegalArgumentException("거래소가 필요합니다.");
        String value = exchange.trim().toUpperCase(Locale.ROOT);
        if (!"UPBIT".equals(value) && !"BITHUMB".equals(value)) {
            throw new IllegalArgumentException("초기 분산 매수는 업비트/빗썸만 지원합니다.");
        }
        return value;
    }

    private static BigDecimal sanitize(BigDecimal amount) {
        if (amount == null) return BigDecimal.ZERO;
        return amount.setScale(0, RoundingMode.DOWN);
    }

    private static Settings validate(SettingsRequest request) {
        int targetSymbolCount = request.targetSymbolCount();
        long target = request.targetKrwPerSymbolPerExchange();
        long reserve = request.cashReserveKrwPerExchange();
        long maxSeed = request.maxSeedBuyKrw();
        long cooldown = request.seedBuyCooldownSeconds();
        if (targetSymbolCount < 1 || targetSymbolCount > 30)
            throw new IllegalArgumentException("추천 코인 개수는 1개 이상 30개 이하여야 합니다.");
        if (target < MIN_SEED_BUY_KRW || target > 100_000_000)
            throw new IllegalArgumentException("코인당 목표금액은 5,000원 이상 1억원 이하여야 합니다.");
        if (reserve < 0 || reserve > 1_000_000_000)
            throw new IllegalArgumentException("거래소별 현금 보유액은 0원 이상 10억원 이하여야 합니다.");
        if (maxSeed < MIN_SEED_BUY_KRW || maxSeed > 100_000_000)
            throw new IllegalArgumentException("1회 최대 초기매수는 5,000원 이상 1억원 이하여야 합니다.");
        if (maxSeed > target)
            throw new IllegalArgumentException("1회 최대 초기매수는 코인당 목표금액보다 클 수 없습니다.");
        if (cooldown < 0 || cooldown > 86_400)
            throw new IllegalArgumentException("재매수 쿨다운은 0초 이상 86,400초 이하여야 합니다.");
        return new Settings(targetSymbolCount, target, reserve, maxSeed, cooldown, null);
    }

    private static Settings toSettings(PortfolioPlanSettingsEntity entity) {
        return new Settings(entity.getTargetSymbolCount(), entity.getTargetKrwPerSymbolPerExchange(),
                entity.getCashReserveKrwPerExchange(), entity.getMaxSeedBuyKrw(),
                entity.getSeedBuyCooldownSeconds(), entity.getUpdatedAt());
    }

    private record PermissionState(String orderReadPermission, String orderCreatePermission) {
        boolean orderReady() {
            return "VERIFIED".equals(orderReadPermission) && !"NOT_GRANTED".equals(orderCreatePermission);
        }
    }

    public record PortfolioPlan(Instant since, Settings settings, long upbitCashKrw, long bithumbCashKrw,
                                List<PlanSymbol> symbols) { }

    public record PlanSymbol(String symbol, long occurrenceCount, double totalExpectedProfitKrw,
                             double averageProfitPercent, Instant lastDetectedAt,
                             Holding upbit, Holding bithumb,
                             long upbitNeedKrw, long bithumbNeedKrw,
                             long upbitSuggestedBuyKrw, long bithumbSuggestedBuyKrw,
                             boolean ready) { }

    public record Holding(BigDecimal quantity, BigDecimal avgBuyPrice, long estimatedValueKrw) { }

    public record SeedBuyRequest(String symbol, String exchange, BigDecimal krwAmount) { }
    public record PairSeedBuyRequest(String symbol, BigDecimal krwAmount) { }

    public record SeedBuyDecision(boolean accepted, String message, String symbol, String exchange,
                                  BigDecimal requestedKrw, Instant decidedAt) { }
    public record PairSeedBuyDecision(boolean accepted, String message, String symbol,
                                      BigDecimal requestedKrwPerExchange, Instant decidedAt) { }

    public record Settings(int targetSymbolCount, long targetKrwPerSymbolPerExchange,
                           long cashReserveKrwPerExchange, long maxSeedBuyKrw,
                           long seedBuyCooldownSeconds, Instant updatedAt) { }

    public record SettingsRequest(int targetSymbolCount, long targetKrwPerSymbolPerExchange,
                                  long cashReserveKrwPerExchange, long maxSeedBuyKrw,
                                  long seedBuyCooldownSeconds) { }
}
