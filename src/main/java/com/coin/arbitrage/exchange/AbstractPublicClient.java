package com.coin.arbitrage.exchange;

import com.coin.arbitrage.config.ArbitrageProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract class AbstractPublicClient implements ExchangeMarketClient {
    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final HttpClient http;
    protected final ObjectMapper mapper;
    protected final ArbitrageProperties properties;

    protected AbstractPublicClient(HttpClient http, ObjectMapper mapper, ArbitrageProperties properties) {
        this.http = http;
        this.mapper = mapper;
        this.properties = properties;
    }

    protected JsonNode get(String url) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= properties.maxRetries(); attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                        .header("Accept", "application/json")
                        .header("User-Agent", "arb-korea/1.0")
                        .GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    ApiRequestMetrics.record(id(), response.statusCode());
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                return mapper.readTree(response.body());
            } catch (Exception error) {
                last = new IllegalStateException(error);
                log.error("API Request Failed | exchange={} attempt={}/{} error={}",
                        id(), attempt, properties.maxRetries(), error.getMessage());
                if (attempt < properties.maxRetries()) {
                    try {
                        Thread.sleep(500L * (1L << (attempt - 1)));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
            }
        }
        ApiRequestMetrics.recordFailure(id());
        throw last == null ? new IllegalStateException("API request failed") : last;
    }

    protected static double number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNumber()) return value.asDouble();
        try {
            return Double.parseDouble(value.asText("0"));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
