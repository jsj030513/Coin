package com.coin.arbitrage.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@Service
public class ExchangeNoticeRiskMonitorService {
    private static final Logger log = LoggerFactory.getLogger(ExchangeNoticeRiskMonitorService.class);
    private static final Pattern PAREN_SYMBOL = Pattern.compile("\\(([A-Z][A-Z0-9]{0,11})\\)");
    private final HttpClient http;
    private final ObjectMapper json;
    private final DelistingRiskService delistingRisk;
    private final boolean enabled;
    private final int upbitPages;
    private final int upbitPerPage;
    private final String upbitUrlTemplate;
    private final boolean upbitZendeskFallbackEnabled;
    private final String upbitZendeskSearchUrlTemplate;
    private final String bithumbUrl;

    public ExchangeNoticeRiskMonitorService(HttpClient http,
                                            ObjectMapper json,
                                            DelistingRiskService delistingRisk,
                                            @Value("${delisting-risk.notice-enabled:true}") boolean enabled,
                                            @Value("${delisting-risk.upbit-notice-pages:10}") int upbitPages,
                                            @Value("${delisting-risk.upbit-notice-per-page:30}") int upbitPerPage,
                                            @Value("${delisting-risk.upbit-notice-url-template:https://api-manager.upbit.com/api/v1/announcements?os=web&page=%d&per_page=%d&category=trade}") String upbitUrlTemplate,
                                            @Value("${delisting-risk.upbit-zendesk-fallback-enabled:false}") boolean upbitZendeskFallbackEnabled,
                                            @Value("${delisting-risk.upbit-zendesk-search-url-template:https://upbitcs.zendesk.com/api/v2/help_center/articles/search.json?query=%s&per_page=30}") String upbitZendeskSearchUrlTemplate,
                                            @Value("${delisting-risk.bithumb-notice-url:https://api.bithumb.com/v1/notices?count=20}") String bithumbUrl) {
        this.http = http;
        this.json = json;
        this.delistingRisk = delistingRisk;
        this.enabled = enabled;
        this.upbitPages = Math.max(1, Math.min(50, upbitPages));
        this.upbitPerPage = Math.max(1, Math.min(30, upbitPerPage));
        this.upbitUrlTemplate = upbitUrlTemplate;
        this.upbitZendeskFallbackEnabled = upbitZendeskFallbackEnabled;
        this.upbitZendeskSearchUrlTemplate = upbitZendeskSearchUrlTemplate;
        this.bithumbUrl = bithumbUrl;
    }

    @Scheduled(fixedDelayString = "${delisting-risk.notice-check-interval-ms:86400000}",
            initialDelayString = "${delisting-risk.notice-check-initial-delay-ms:120000}")
    public void refreshRiskSymbolsFromNotices() {
        if (!enabled) return;
        try {
            refreshExchange("BITHUMB", fetch(bithumbUrl));
        } catch (Exception error) {
            log.warn("Bithumb notice risk refresh failed", error);
        }
        try {
            refreshExchange("UPBIT", fetchUpbitNotices());
        } catch (Exception error) {
            log.warn("Upbit notice risk refresh failed", error);
        }
    }

    private void refreshExchange(String exchange, String body) {
        if (body == null || body.isBlank()) {
            log.warn("{} notice body was empty. Existing configured risk symbols remain active.", exchange);
            return;
        }
        NoticeChanges changes = noticeChanges(body);
        delistingRisk.applyNoticeChanges(exchange, changes.added(), changes.released());
        if (!changes.added().isEmpty() || !changes.released().isEmpty())
            log.info("{} notice risk changes: added={}, released={}", exchange, changes.added(), changes.released());
    }

    private String fetchUpbitNotices() {
        StringBuilder result = new StringBuilder();
        for (int page = 1; page <= upbitPages; page++) {
            String body = fetch(upbitUrlTemplate.formatted(page, upbitPerPage));
            if (body == null || body.isBlank()) break;
            result.append('\n').append(body);
        }
        if (upbitZendeskFallbackEnabled) {
            result.append('\n').append(fetchUpbitZendeskSearches());
        }
        return result.toString();
    }

    private String fetchUpbitZendeskSearches() {
        List<CompletableFuture<String>> requests = new ArrayList<>();
        for (String query : java.util.List.of("거래지원 종료", "거래 유의", "유의종목", "출금 지원 종료", "상장폐지")) {
            try {
                String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
                requests.add(fetchAsync(upbitZendeskSearchUrlTemplate.formatted(encoded)));
            } catch (Exception error) {
                log.warn("Upbit Zendesk notice search failed | query={}", query, error);
            }
        }
        if (requests.isEmpty()) return "";
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                .orTimeout(10, TimeUnit.SECONDS)
                .exceptionally(error -> null)
                .join();
        StringBuilder result = new StringBuilder();
        for (CompletableFuture<String> request : requests) {
            if (!request.isDone() || request.isCompletedExceptionally()) continue;
            String body = request.getNow("");
            if (body != null && !body.isBlank()) result.append('\n').append(body);
        }
        return result.toString();
    }

    private String fetch(String url) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("User-Agent", "coin-arbitrage-risk-monitor")
                    .GET();
            if (url.contains("api-manager.upbit.com")) {
                builder.header("Referer", "https://upbit.com/service_center/notice")
                        .header("Origin", "https://upbit.com");
            }
            HttpRequest request = builder.build();
            HttpResponse<String> response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(8, TimeUnit.SECONDS)
                    .join();
            if (response.statusCode() / 100 != 2) {
                log.warn("Notice fetch failed | url={} status={}", url, response.statusCode());
                return "";
            }
            return response.body();
        } catch (Exception error) {
            log.warn("Notice fetch failed | url={}", url, error);
            return "";
        }
    }

    private CompletableFuture<String> fetchAsync(String url) {
        try {
            HttpRequest request = request(url);
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(8, TimeUnit.SECONDS)
                    .handle((response, error) -> {
                        if (error != null) {
                            log.warn("Notice async fetch failed | url={}", url, error);
                            return "";
                        }
                        if (response.statusCode() / 100 != 2) {
                            log.warn("Notice async fetch failed | url={} status={}", url, response.statusCode());
                            return "";
                        }
                        return response.body();
                    });
        } catch (Exception error) {
            log.warn("Notice async fetch failed | url={}", url, error);
            return CompletableFuture.completedFuture("");
        }
    }

    private HttpRequest request(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("User-Agent", "coin-arbitrage-risk-monitor")
                .GET();
        if (url.contains("api-manager.upbit.com")) {
            builder.header("Referer", "https://upbit.com/service_center/notice")
                    .header("Origin", "https://upbit.com");
        }
        return builder.build();
    }

    Set<String> riskySymbolsFromNoticeBody(String body) {
        return noticeChanges(body).added();
    }

    NoticeChanges noticeChanges(String body) {
        ArrayList<NoticeEvent> events = new ArrayList<>();
        try {
            List<String> documents = body.stripLeading().startsWith("[")
                    ? List.of(body) : body.lines().filter(value -> !value.isBlank()).toList();
            for (String line : documents) {
                JsonNode root = json.readTree(line);
                JsonNode notices = root.isArray() ? root : root.has("data") ? root.path("data").path("notices") : json.readTree("[" + line + "]");
                for (JsonNode notice : notices) {
                String title = notice.path("title").asText("");
                String compact = title.replaceAll("\\s+", "");
                boolean released = compact.contains("유의종목지정해제") || compact.contains("투자유의종목지정해제")
                        || compact.contains("거래유의종목지정해제");
                boolean risky = compact.contains("거래지원종료") || compact.contains("거래유의종목지정")
                        || compact.contains("투자유의종목지정") || compact.contains("유의종목지정")
                        || compact.contains("유의종목지정기간연장") || compact.contains("출금지원종료");
                if (!released && !risky) continue;
                // A notice can name both newly designated and released assets. Avoid guessing in that case.
                if (released && (compact.contains("및") || compact.contains("·"))) continue;
                Set<String> symbols = extractSymbols(title);
                String date = notice.path("published_at").asText(notice.path("listed_at").asText(""));
                for (String symbol : symbols) events.add(new NoticeEvent(date, symbol, !released));
                }
            }
        } catch (Exception error) {
            throw new IllegalArgumentException("Unable to parse exchange notices", error);
        }
        events.sort(Comparator.comparing(NoticeEvent::date));
        java.util.Map<String, Boolean> latest = new java.util.HashMap<>();
        for (NoticeEvent event : events) latest.put(event.symbol(), event.risky());
        Set<String> added = new HashSet<>();
        Set<String> released = new HashSet<>();
        latest.forEach((symbol, risky) -> { if (risky) added.add(symbol); else released.add(symbol); });
        return new NoticeChanges(Set.copyOf(added), Set.copyOf(released));
    }

    private Set<String> extractSymbols(String text) {
        Set<String> symbols = new HashSet<>();
        java.util.regex.Matcher matcher = PAREN_SYMBOL.matcher(text);
        while (matcher.find()) symbols.add(matcher.group(1).toUpperCase(Locale.ROOT) + "/KRW");
        return symbols;
    }

    record NoticeChanges(Set<String> added, Set<String> released) {}
    private record NoticeEvent(String date, String symbol, boolean risky) {}
}
