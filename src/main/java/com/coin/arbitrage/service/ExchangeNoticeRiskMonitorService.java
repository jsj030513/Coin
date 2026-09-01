package com.coin.arbitrage.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class ExchangeNoticeRiskMonitorService {
    private static final Logger log = LoggerFactory.getLogger(ExchangeNoticeRiskMonitorService.class);
    private static final Pattern JSON_TEXT_FIELD = Pattern.compile(
            "\"(?:title|subject|content|body|text|category)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAREN_SYMBOL = Pattern.compile("\\(([A-Z0-9]{2,12})\\)");
    private static final Pattern MARKET_SYMBOL = Pattern.compile("\\b(?:KRW[-_/])([A-Z0-9]{2,12})\\b|\\b([A-Z0-9]{2,12})(?:[-_/]KRW)\\b");
    private static final Pattern BARE_SYMBOL = Pattern.compile("\\b[A-Z0-9]{2,12}\\b");
    private static final Set<String> STOPWORDS = Set.of(
            "KRW", "BTC", "USDT", "USD", "API", "HTTP", "HTTPS", "WEB", "OPEN", "OPENAPI",
            "NFT", "ETF", "VIP", "PC", "IOS", "AOS", "UTC", "KST", "IP", "AI"
    );
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
        Set<String> detected = riskySymbolsFromNoticeBody(body);
        delistingRisk.replaceNoticeDetected(exchange, detected);
        if (!detected.isEmpty()) {
            log.warn("Delisting/trading-risk symbols detected from {} notices: {}", exchange, detected);
        } else {
            log.info("No delisting/trading-risk symbols detected from {} notices", exchange);
        }
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
        Set<String> result = new HashSet<>();
        Matcher matcher = JSON_TEXT_FIELD.matcher(body);
        while (matcher.find()) {
            String text = decodeJsonString(matcher.group(1));
            if (riskNotice(text)) result.addAll(extractSymbols(text));
        }
        return Set.copyOf(result);
    }

    private boolean riskNotice(String text) {
        String value = text == null ? "" : text.replace(" ", "");
        if (value.isBlank()) return false;
        if (value.contains("입출금일시중단") || value.contains("입출금중단")) {
            return value.contains("거래지원종료") || value.contains("상장폐지") || value.contains("출금지원종료");
        }
        return value.contains("거래지원종료")
                || value.contains("거래유의")
                || value.contains("투자유의")
                || value.contains("유의종목")
                || value.contains("상장폐지")
                || value.contains("출금지원종료")
                || value.contains("입출금종료")
                || value.contains("마켓종료")
                || value.contains("거래종료");
    }

    private Set<String> extractSymbols(String text) {
        Set<String> symbols = new HashSet<>();
        addMatches(symbols, PAREN_SYMBOL.matcher(text), 1);
        Matcher market = MARKET_SYMBOL.matcher(text);
        while (market.find()) {
            addSymbol(symbols, market.group(1) == null ? market.group(2) : market.group(1));
        }
        Matcher bare = BARE_SYMBOL.matcher(text);
        while (bare.find()) addSymbol(symbols, bare.group());
        return symbols;
    }

    private void addMatches(Set<String> symbols, Matcher matcher, int group) {
        while (matcher.find()) addSymbol(symbols, matcher.group(group));
    }

    private void addSymbol(Set<String> symbols, String symbol) {
        String value = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (value.length() < 2 || STOPWORDS.contains(value)) return;
        symbols.add(value + "/KRW");
    }

    private String decodeJsonString(String escaped) {
        try {
            return json.readTree("\"" + escaped + "\"").asText("");
        } catch (Exception ignored) {
            return escaped.replace("\\/", "/");
        }
    }
}
