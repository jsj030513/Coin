package com.coin.arbitrage.service;

import com.coin.arbitrage.domain.OrderResult;
import com.coin.arbitrage.persistence.LiveOrderEntity;
import com.coin.arbitrage.persistence.LiveOrderRepository;
import com.coin.arbitrage.persistence.UserAccountEntity;
import com.coin.arbitrage.persistence.UserAccountRepository;
import java.math.BigDecimal;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class LiveOrderHistoryService {
    private final LiveOrderRepository orders;
    private final UserAccountRepository users;
    private final TelegramNotificationService telegram;

    public LiveOrderHistoryService(LiveOrderRepository orders, UserAccountRepository users,
                                   TelegramNotificationService telegram) {
        this.orders = orders;
        this.users = users;
        this.telegram = telegram;
    }

    public LiveOrderEntity record(String username, String side, BigDecimal requestedKrw,
                                  String source, OrderResult result) {
        UserAccountEntity user = users.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다."));
        LiveOrderEntity saved = orders.save(new LiveOrderEntity(user, result.exchange(), result.symbol(), side,
                result.orderId(), zeroIfNull(requestedKrw), zeroIfNull(result.quantity()),
                zeroIfNull(result.executedPrice()), result.status(), source));
        telegram.notifyLiveOrderSubmitted(username, side, zeroIfNull(requestedKrw)
                .setScale(0, java.math.RoundingMode.DOWN).longValue(), source, result);
        return saved;
    }

    public List<LiveOrderEntity> recent(String username) {
        return orders.findTop100ByUserUsernameOrderByCreatedAtDesc(username);
    }

    public Page<LiveOrderEntity> search(String username, String exchange, String side, String symbol,
                                        String source, Instant from, Instant to, int page, int size) {
        Specification<LiveOrderEntity> filter = (root, query, builder) ->
                builder.equal(root.get("user").get("username"), username);
        if (hasText(exchange)) filter = filter.and(equalsIgnoreCase("exchange", exchange));
        if (hasText(side)) filter = filter.and(equalsIgnoreCase("side", side));
        if (hasText(source)) filter = filter.and(equalsIgnoreCase("source", source));
        if (hasText(symbol)) {
            String keyword = "%" + symbol.trim().toUpperCase() + "%";
            filter = filter.and((root, query, builder) -> builder.like(builder.upper(root.get("symbol")), keyword));
        }
        if (from != null) filter = filter.and((root, query, builder) -> builder.greaterThanOrEqualTo(root.get("createdAt"), from));
        if (to != null) filter = filter.and((root, query, builder) -> builder.lessThan(root.get("createdAt"), to));
        PageRequest paging = PageRequest.of(Math.max(0, page), Math.max(10, Math.min(100, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return orders.findAll(filter, paging);
    }

    public LiveOrderEntity latest(String username) {
        return search(username, null, null, null, null, null, null, 0, 10)
                .stream().findFirst().orElse(null);
    }

    public List<LiveOrderEntity> pendingForSync() {
        return orders.findTop200ByStatusInAndCreatedAtAfterOrderByCreatedAtAsc(
                List.of("wait", "watch"), Instant.now().minusSeconds(7 * 24 * 60 * 60));
    }

    public LiveOrderEntity updateExecution(long id, String status, BigDecimal quantity, BigDecimal price) {
        LiveOrderEntity order = orders.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("주문 기록을 찾을 수 없습니다."));
        order.updateExecution(status, quantity, price);
        return orders.save(order);
    }

    public boolean recentlySubmitted(String username, String exchange, String symbol,
                                     String source, long cooldownSeconds) {
        return cooldownSeconds > 0 && orders.existsByUserUsernameAndExchangeAndSymbolAndSourceAndCreatedAtAfter(
                username, exchange, symbol, source, Instant.now().minusSeconds(cooldownSeconds));
    }

    public boolean recentlySubmitted(String username, String source, long cooldownSeconds) {
        return cooldownSeconds > 0 && orders.existsByUserUsernameAndSourceAndCreatedAtAfter(
                username, source, Instant.now().minusSeconds(cooldownSeconds));
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Specification<LiveOrderEntity> equalsIgnoreCase(String field, String value) {
        String normalized = value.trim().toUpperCase();
        return (root, query, builder) -> builder.equal(builder.upper(root.get(field)), normalized);
    }
}
