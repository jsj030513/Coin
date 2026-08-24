package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.LiveOrderEntity;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SystemStatusService {
    private final Instant startedAt = Instant.now();
    private final ArbitrageEngine engine;
    private final TradingSettingsService trading;
    private final ExchangeConnectionService connections;
    private final LiveOrderHistoryService orders;
    private final long staleAfterMillis;

    public SystemStatusService(ArbitrageEngine engine, TradingSettingsService trading,
                               ExchangeConnectionService connections, LiveOrderHistoryService orders,
                               @Value("${arbitrage.scan-interval-ms:3000}") long scanIntervalMillis) {
        this.engine = engine;
        this.trading = trading;
        this.connections = connections;
        this.orders = orders;
        this.staleAfterMillis = Math.max(60_000, scanIntervalMillis * 10);
    }

    public Status status(String username) {
        Instant now = Instant.now();
        ArbitrageEngine.ScanStatus scan = engine.status();
        boolean stale = scan.lastCompletedAt() == null
                || scan.lastCompletedAt().plusMillis(staleAfterMillis).isBefore(now);
        var connectionRows = connections.list(username).stream()
                .filter(row -> "UPBIT".equals(row.exchange()) || "BITHUMB".equals(row.exchange())).toList();
        long verified = connectionRows.stream().filter(row -> "VERIFIED".equals(row.status())).count();
        LiveOrderEntity latest = orders.latest(username);
        TradingSettingsService.Status tradingStatus = trading.status(username);
        return new Status(now, startedAt, Duration.between(startedAt, now).toSeconds(),
                !stale && scan.lastError() == null, stale, scan,
                verified, connectionRows.size(), tradingStatus, latest == null ? null : LastOrder.from(latest));
    }

    public record Status(Instant serverTime, Instant startedAt, long uptimeSeconds,
                         boolean scanHealthy, boolean scanStale, ArbitrageEngine.ScanStatus scan,
                         long verifiedConnections, int totalConnections,
                         TradingSettingsService.Status trading, LastOrder lastOrder) { }

    public record LastOrder(String exchange, String symbol, String side, String status, Instant createdAt) {
        static LastOrder from(LiveOrderEntity order) {
            return new LastOrder(order.getExchange(), order.getSymbol(), order.getSide(),
                    order.getStatus(), order.getCreatedAt());
        }
    }
}
