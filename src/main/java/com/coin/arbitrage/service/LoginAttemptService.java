package com.coin.arbitrage.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class LoginAttemptService {
    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public void failed(String ip, String username) {
        Instant now = Instant.now();
        for (String key : keys(ip, username)) {
            attempts.compute(key, (ignored, previous) -> {
                int failures = previous == null || previous.lastFailure().plus(WINDOW).isBefore(now)
                        ? 1 : previous.failures() + 1;
                Instant lockedUntil = failures >= MAX_FAILURES ? now.plus(LOCK_DURATION) : null;
                return new Attempt(failures, now, lockedUntil);
            });
        }
    }

    public void succeeded(String ip, String username) {
        keys(ip, username).forEach(attempts::remove);
    }

    public long retryAfterSeconds(String ip, String username) {
        Instant now = Instant.now();
        long remaining = keys(ip, username).stream().map(attempts::get)
                .filter(value -> value != null && value.lockedUntil() != null && value.lockedUntil().isAfter(now))
                .mapToLong(value -> Duration.between(now, value.lockedUntil()).toSeconds()).max().orElse(0);
        if (remaining == 0) keys(ip, username).forEach(key -> attempts.computeIfPresent(key,
                (ignored, value) -> value.lastFailure().plus(WINDOW).isBefore(now) ? null : value));
        return remaining;
    }

    private static java.util.List<String> keys(String ip, String username) {
        String safeIp = ip == null || ip.isBlank() ? "unknown" : ip.trim();
        String safeUser = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return java.util.List.of("ip:" + safeIp, "user:" + safeUser);
    }

    private record Attempt(int failures, Instant lastFailure, Instant lockedUntil) { }
}
