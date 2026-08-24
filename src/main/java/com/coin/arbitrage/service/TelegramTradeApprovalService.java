package com.coin.arbitrage.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class TelegramTradeApprovalService {
    private static final long VALID_SECONDS = 600;
    private final ConcurrentHashMap<String, PendingApproval> pending = new ConcurrentHashMap<>();
    private final LiveBalanceService balances;
    private final LiveExchangeOrderService orders;
    private final LiveOrderHistoryService history;
    private final TradingSettingsService trading;
    private final PortfolioPlanService plans;
    private final NotificationSettingsService notificationSettings;
    private final TelegramNotificationService telegram;
    private final ArbitrageEngine engine;

    public TelegramTradeApprovalService(LiveBalanceService balances, LiveExchangeOrderService orders,
                                        LiveOrderHistoryService history, TradingSettingsService trading,
                                        PortfolioPlanService plans, NotificationSettingsService notificationSettings,
                                        TelegramNotificationService telegram, ArbitrageEngine engine) {
        this.balances = balances;
        this.orders = orders;
        this.history = history;
        this.trading = trading;
        this.plans = plans;
        this.notificationSettings = notificationSettings;
        this.telegram = telegram;
        this.engine = engine;
    }

    public ApprovalView requestLiquidation(String username) {
        return requestLiquidation(username, java.util.Set.of());
    }

    public ApprovalView requestLiquidation(String username, java.util.Set<String> selectedHoldings) {
        trading.emergencyStop(username);
        LiveBalanceService.LiveBalanceResponse snapshot = balances.snapshot(username);
        ensureConnected(snapshot);
        List<Holding> holdings = snapshot.balances().stream()
                .filter(value -> !"KRW".equals(value.asset()))
                .filter(value -> value.free().compareTo(BigDecimal.ZERO) > 0)
                .filter(value -> selectedHoldings.isEmpty()
                        || selectedHoldings.contains(value.exchange() + "|" + value.asset() + "/KRW"))
                .map(value -> liquidationHolding(value.exchange(), value.asset() + "/KRW", value.free()))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (holdings.isEmpty()) throw new IllegalStateException("시장가로 매도할 보유 코인이 없습니다.");
        String token = token();
        pending.put(token, new PendingApproval(username, notificationSettings.telegramChatId(username),
                Action.LIQUIDATE_ALL, holdings, List.of(), BigDecimal.ZERO, Instant.now().plusSeconds(VALID_SECONDS)));
        String exchanges = holdings.stream().map(Holding::exchange).distinct().count() + "개 거래소";
        long direct = holdings.stream().filter(value -> !value.topUpRequired()).count();
        long topUp = holdings.size() - direct;
        long estimated = holdings.stream().map(Holding::estimatedSellKrw)
                .reduce(BigDecimal.ZERO, BigDecimal::add).longValue();
        telegram.sendTradeApproval(username, token, "모든 보유 코인 시장가 매도",
                "%s · 총 %d개 자산\n직접 매도 %d건 · 5천원 보충 후 매도 %d건\n현재 호가 예상 회수 %,d원\nKRW와 주문 중 잠긴 수량은 제외됩니다."
                        .formatted(exchanges, holdings.size(), direct, topUp, estimated));
        return new ApprovalView("LIQUIDATE_ALL", holdings.size(), Instant.now().plusSeconds(VALID_SECONDS));
    }

    public ApprovalView requestRecommendedSeed(String username) {
        trading.emergencyStop(username);
        PortfolioPlanService.PortfolioPlan plan = plans.plan(username);
        List<String> symbols = plan.symbols().stream().limit(9).map(PortfolioPlanService.PlanSymbol::symbol).toList();
        if (symbols.isEmpty()) throw new IllegalStateException("추천할 탐지 데이터가 아직 없습니다.");
        long amountValue = Math.max(5_000, Math.min(plan.settings().targetKrwPerSymbolPerExchange(),
                plan.settings().maxSeedBuyKrw()));
        long availablePerExchange = Math.max(0, Math.min(plan.upbitCashKrw(), plan.bithumbCashKrw())
                - plan.settings().cashReserveKrwPerExchange());
        int affordable = (int) Math.min(symbols.size(), availablePerExchange / amountValue);
        if (affordable <= 0) throw new IllegalStateException("거래소별 현금 보유액을 제외하면 자동매수 가능한 KRW가 부족합니다.");
        symbols = List.copyOf(symbols.subList(0, affordable));
        BigDecimal amount = BigDecimal.valueOf(amountValue);
        String token = token();
        pending.put(token, new PendingApproval(username, notificationSettings.telegramChatId(username),
                Action.BUY_RECOMMENDED, List.of(), symbols, amount, Instant.now().plusSeconds(VALID_SECONDS)));
        telegram.sendTradeApproval(username, token, "추천 코인 양쪽 거래소 동일금액 매수",
                "%s\n총 %d개 · 거래소별 코인당 %,d원".formatted(String.join(", ", symbols), symbols.size(), amountValue));
        return new ApprovalView("BUY_RECOMMENDED", symbols.size(), Instant.now().plusSeconds(VALID_SECONDS));
    }

    public String handleCallback(String token, boolean approve, String chatId) {
        PendingApproval request = pending.get(token);
        if (request == null || request.expiresAt().isBefore(Instant.now())) {
            pending.remove(token);
            return "요청이 없거나 만료되었습니다.";
        }
        if (!request.chatId().equals(chatId)) return "이 계정의 승인 요청이 아닙니다.";
        if (!pending.remove(token, request)) return "이미 처리된 요청입니다.";
        if (!approve) {
            telegram.sendTradeApprovalResult(request.username(), "사용자가 거래 요청을 거절했습니다.");
            return "거절했습니다.";
        }
        if (trading.active(request.username())) return "자동거래를 먼저 비상 정지해야 합니다.";
        CompletableFuture.runAsync(() -> {
            if (request.action() == Action.LIQUIDATE_ALL) executeLiquidation(request);
            else executeRecommendedBuy(request);
        });
        return "승인했습니다. 주문 처리를 시작합니다.";
    }

    private String executeLiquidation(PendingApproval request) {
        LiveBalanceService.LiveBalanceResponse current = balances.snapshot(request.username());
        int submitted = 0;
        List<String> failures = new ArrayList<>();
        for (Holding holding : request.holdings()) {
            try {
                if (holding.topUpRequired()) {
                    BigDecimal topUpAmount = BigDecimal.valueOf(orders.minOrderKrw());
                    var buy = orders.buyMarket(request.username(), holding.exchange(), holding.symbol(), topUpAmount);
                    history.record(request.username(), "BUY", topUpAmount, "LIQUIDATION_TOP_UP", buy);
                    waitForTerminal(request.username(), holding.exchange(), buy.orderId());
                    pause(500);
                    current = balances.snapshot(request.username());
                }
                BigDecimal available = available(current, holding);
                if (available.compareTo(BigDecimal.ZERO) <= 0) continue;
                ArbitrageEngine.SellEstimate estimate = engine.estimateSell(
                        holding.symbol(), holding.exchange(), available);
                if (!estimate.executable()
                        || estimate.quoteAmount().compareTo(BigDecimal.valueOf(orders.minOrderKrw())) < 0
                        || !orders.orderChanceReady(request.username(), holding.exchange(), holding.symbol(),
                        false, available, estimate.quoteAmount())) {
                    failures.add(holding.exchange() + " " + holding.symbol());
                    continue;
                }
                var result = orders.sellMarket(request.username(), holding.exchange(), holding.symbol(), available);
                history.record(request.username(), "SELL", BigDecimal.ZERO, "TELEGRAM_LIQUIDATION", result);
                submitted++;
                pause(300);
            } catch (Exception error) {
                failures.add(holding.exchange() + " " + holding.symbol());
            }
        }
        String message = "전체 매도 주문 %d건 접수 · 실패/최소금액 미달 %d건".formatted(submitted, failures.size());
        if (!failures.isEmpty()) message += "\n확인 필요: " + String.join(", ", failures);
        telegram.sendTradeApprovalResult(request.username(), message);
        return message;
    }

    private String executeRecommendedBuy(PendingApproval request) {
        int completed = 0;
        List<String> failures = new ArrayList<>();
        for (String symbol : request.symbols()) {
            PortfolioPlanService.PairSeedBuyDecision result = plans.approvePairSeedBuy(request.username(),
                    new PortfolioPlanService.PairSeedBuyRequest(symbol, request.amount()));
            if (result.accepted()) completed++;
            else failures.add(symbol);
            pause(250);
        }
        String message = "추천 코인 양쪽 매수 %d/%d개 접수".formatted(completed, request.symbols().size());
        if (!failures.isEmpty()) message += "\n확인 필요: " + String.join(", ", failures);
        telegram.sendTradeApprovalResult(request.username(), message);
        return message;
    }

    private static void ensureConnected(LiveBalanceService.LiveBalanceResponse snapshot) {
        boolean all = snapshot.statuses().stream()
                .filter(value -> "UPBIT".equals(value.exchange()) || "BITHUMB".equals(value.exchange()))
                .allMatch(LiveBalanceService.ExchangeBalanceStatus::connected);
        if (!all || snapshot.statuses().size() < 2) throw new IllegalStateException("양쪽 거래소 잔고 조회가 모두 정상이어야 합니다.");
    }

    private Holding liquidationHolding(String exchange, String symbol, BigDecimal quantity) {
        ArbitrageEngine.SellEstimate estimate = engine.estimateSell(symbol, exchange, quantity);
        if (!estimate.executable()) return null;
        return new Holding(exchange, symbol, quantity, estimate.quoteAmount(),
                estimate.quoteAmount().compareTo(BigDecimal.valueOf(orders.minOrderKrw())) < 0);
    }

    private static BigDecimal available(LiveBalanceService.LiveBalanceResponse snapshot, Holding holding) {
        return snapshot.balances().stream()
                .filter(value -> holding.exchange().equals(value.exchange()))
                .filter(value -> holding.symbol().equals(value.asset() + "/KRW"))
                .map(LiveBalanceService.LiveAssetBalance::free).findFirst().orElse(BigDecimal.ZERO);
    }

    private void waitForTerminal(String username, String exchange, String orderId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            var status = orders.getOrderStatus(username, exchange, orderId);
            if (("done".equalsIgnoreCase(status.status()) || "cancel".equalsIgnoreCase(status.status()))
                    && status.executedQuantity().signum() > 0) return;
            pause(500);
        }
        throw new IllegalStateException("보충 매수 체결 확인 시간 초과");
    }

    private static String token() { return UUID.randomUUID().toString().replace("-", ""); }
    private static void pause(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
    private enum Action { LIQUIDATE_ALL, BUY_RECOMMENDED }
    private record Holding(String exchange, String symbol, BigDecimal quantity,
                           BigDecimal estimatedSellKrw, boolean topUpRequired) { }
    private record PendingApproval(String username, String chatId, Action action, List<Holding> holdings,
                                   List<String> symbols, BigDecimal amount, Instant expiresAt) { }
    public record ApprovalView(String action, int itemCount, Instant expiresAt) { }
}
