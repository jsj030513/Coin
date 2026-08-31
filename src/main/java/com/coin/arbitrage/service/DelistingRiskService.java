package com.coin.arbitrage.service;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DelistingRiskService {
    private final Map<String, Set<String>> blockedByExchange;
    private final Map<String, Instant> lastAlertAt = new ConcurrentHashMap<>();
    private final TelegramNotificationService telegram;

    public DelistingRiskService(TelegramNotificationService telegram,
                                @Value("${delisting-risk.upbit-symbols:BONK/KRW,TT/KRW}") String upbitSymbols,
                                @Value("${delisting-risk.bithumb-symbols:}") String bithumbSymbols) {
        this.telegram = telegram;
        this.blockedByExchange = Map.of(
                "UPBIT", parse(upbitSymbols),
                "BITHUMB", parse(bithumbSymbols)
        );
    }

    public boolean risky(String exchange, String symbol) {
        return blockedByExchange.getOrDefault(normalizeExchange(exchange), Set.of())
                .contains(normalizeSymbol(symbol));
    }

    public boolean riskyRoute(String symbol, String buyExchange, String sellExchange) {
        return risky(buyExchange, symbol) || risky(sellExchange, symbol);
    }

    public String reason(String exchange, String symbol) {
        if (!risky(exchange, symbol)) return "";
        return "%s %s은(는) 거래지원/출금 종료 위험 목록에 있어 신규 자동매수와 차익거래에서 제외됩니다."
                .formatted(normalizeExchange(exchange), normalizeSymbol(symbol));
    }

    public void notifyHoldingRisk(String username, String exchange, String symbol, long estimatedValueKrw) {
        if (!risky(exchange, symbol)) return;
        String key = username + ":" + normalizeExchange(exchange) + ":" + normalizeSymbol(symbol);
        Instant now = Instant.now();
        Instant previous = lastAlertAt.get(key);
        if (previous != null && previous.plusSeconds(6 * 60 * 60).isAfter(now)) return;
        lastAlertAt.put(key, now);
        telegram.notifyDelistingRisk(username, normalizeExchange(exchange), normalizeSymbol(symbol), estimatedValueKrw);
    }

    private static Set<String> parse(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return java.util.Arrays.stream(value.split(","))
                .map(DelistingRiskService::normalizeSymbol)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizeExchange(String exchange) {
        return exchange == null ? "" : exchange.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null) return "";
        String value = symbol.trim().toUpperCase(Locale.ROOT);
        return value.contains("/") ? value : value + "/KRW";
    }
}
