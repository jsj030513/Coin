package com.coin.arbitrage.service;

import com.coin.arbitrage.config.LiveTradingProperties;
import com.coin.arbitrage.domain.OrderResult;
import com.coin.arbitrage.domain.OrderStatus;
import com.coin.arbitrage.persistence.ExchangeConnectionEntity;
import com.coin.arbitrage.persistence.ExchangeConnectionEntity.Exchange;
import com.coin.arbitrage.persistence.ExchangeConnectionRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LiveExchangeOrderService {
    private static final Logger log = LoggerFactory.getLogger(LiveExchangeOrderService.class);

    private final ExchangeConnectionRepository connections;
    private final CredentialEncryptionService encryption;
    private final ObjectMapper json;
    private final LiveTradingProperties properties;
    private final ExchangeConnectionService connectionService;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public LiveExchangeOrderService(ExchangeConnectionRepository connections,
                                    CredentialEncryptionService encryption,
                                    ObjectMapper json,
                                    LiveTradingProperties properties,
                                    ExchangeConnectionService connectionService) {
        this.connections = connections;
        this.encryption = encryption;
        this.json = json;
        this.properties = properties;
        this.connectionService = connectionService;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public boolean seedBuyEnabled() {
        return properties.seedBuyEnabled();
    }

    public long maxOrderKrw() {
        return properties.maxOrderKrw();
    }

    public long minOrderKrw() {
        return properties.minOrderKrw();
    }

    public boolean orderReady(String username, String exchangeName) {
        try {
            ExchangeConnectionEntity connection = connections.findByUserUsernameAndExchange(username, parse(exchangeName))
                    .filter(value -> value.getStatus() == ExchangeConnectionEntity.Status.VERIFIED)
                    .orElse(null);
            return connection != null && "VERIFIED".equals(connection.getOrderReadPermission())
                    && !"NOT_GRANTED".equals(connection.getOrderCreatePermission());
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    public boolean orderChanceReady(String username, String exchangeName, String symbol,
                                    boolean buy, BigDecimal requestedAmount) {
        return orderChanceReady(username, exchangeName, symbol, buy, requestedAmount, requestedAmount);
    }

    public boolean orderChanceReady(String username, String exchangeName, String symbol,
                                    boolean buy, BigDecimal requestedAmount, BigDecimal estimatedTotalKrw) {
        try {
            Exchange exchange = parse(exchangeName);
            ConnectionSecrets secrets = secrets(username, exchange);
            String market = market(exchange, symbol);
            String query = "market=" + encode(market);
            String baseUrl = exchange == Exchange.UPBIT ? "https://api.upbit.com" : "https://api.bithumb.com";
            String algorithm = exchange == Exchange.UPBIT ? "HmacSHA512" : "HmacSHA256";
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/orders/chance?" + query))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + jwt(secrets.accessKey(), secrets.secretKey(), algorithm, query))
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Order chance check rejected | exchange={} symbol={} status={} body={}",
                        exchangeName, symbol, response.statusCode(), truncate(response.body()));
                return false;
            }
            JsonNode root = json.readTree(response.body());
            connectionService.observeFees(username, exchangeName,
                    percentRate(root.path("bid_fee")), percentRate(root.path("ask_fee")));
            if (buy) {
                BigDecimal minTotal = decimal(root.path("market").path("bid").path("min_total"));
                BigDecimal balance = decimal(root.path("bid_account").path("balance"));
                return requestedAmount.compareTo(minTotal) >= 0 && balance.compareTo(requestedAmount) >= 0;
            }
            BigDecimal minTotal = decimal(root.path("market").path("ask").path("min_total"));
            BigDecimal balance = decimal(root.path("ask_account").path("balance"));
            return sellConstraintsSatisfied(requestedAmount, estimatedTotalKrw, minTotal, balance);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception error) {
            log.warn("Order chance check failed | exchange={} symbol={} reason={}",
                    exchangeName, symbol, error.getMessage());
            return false;
        }
    }

    public OrderResult buyMarket(String username, String exchangeName, String symbol, BigDecimal krwAmount) {
        if (!properties.enabled()) {
            throw new IllegalStateException("실거래 주문이 잠겨 있습니다. LIVE_TRADING_ENABLED=true가 필요합니다.");
        }
        return buyMarketInternal(username, exchangeName, symbol, krwAmount);
    }

    public OrderResult buySeedMarket(String username, String exchangeName, String symbol, BigDecimal krwAmount) {
        if (!properties.seedBuyEnabled()) {
            throw new IllegalStateException("초기 매수 테스트가 잠겨 있습니다. LIVE_SEED_BUY_ENABLED=true가 필요합니다.");
        }
        return buyMarketInternal(username, exchangeName, symbol, krwAmount);
    }

    private OrderResult buyMarketInternal(String username, String exchangeName, String symbol, BigDecimal krwAmount) {
        guardAmount(krwAmount);
        Exchange exchange = parse(exchangeName);
        ConnectionSecrets secrets = secrets(username, exchange);
        String market = market(exchange, symbol);
        return switch (exchange) {
            case UPBIT -> placeJwtOrder("https://api.upbit.com", "UPBIT", "HmacSHA512", secrets,
                    market, "bid", null, krwAmount.setScale(0, RoundingMode.DOWN));
            case BITHUMB -> placeJwtOrder("https://api.bithumb.com", "BITHUMB", "HmacSHA256", secrets,
                    market, "bid", null, krwAmount.setScale(0, RoundingMode.DOWN));
            case COINONE, KORBIT -> throw new UnsupportedOperationException("Live orders support Upbit/Bithumb only");
        };
    }

    public OrderResult sellMarket(String username, String exchangeName, String symbol, BigDecimal quantity) {
        if (!properties.enabled()) {
            throw new IllegalStateException("실거래 주문이 잠겨 있습니다. LIVE_TRADING_ENABLED=true가 필요합니다.");
        }
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("매도 수량이 0보다 커야 합니다.");
        }
        Exchange exchange = parse(exchangeName);
        ConnectionSecrets secrets = secrets(username, exchange);
        String market = market(exchange, symbol);
        return switch (exchange) {
            case UPBIT -> placeJwtOrder("https://api.upbit.com", "UPBIT", "HmacSHA512", secrets,
                    market, "ask", quantity, null);
            case BITHUMB -> placeJwtOrder("https://api.bithumb.com", "BITHUMB", "HmacSHA256", secrets,
                    market, "ask", quantity, null);
            case COINONE, KORBIT -> throw new UnsupportedOperationException("Live orders support Upbit/Bithumb only");
        };
    }

    public OrderStatus getOrderStatus(String username, String exchangeName, String orderId) {
        Exchange exchange = parse(exchangeName);
        ConnectionSecrets secrets = secrets(username, exchange);
        String query = "uuid=" + encode(orderId);
        String baseUrl = exchange == Exchange.UPBIT ? "https://api.upbit.com" : "https://api.bithumb.com";
        String algorithm = exchange == Exchange.UPBIT ? "HmacSHA512" : "HmacSHA256";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/order?" + query))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + jwt(secrets.accessKey(), secrets.secretKey(), algorithm, query))
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
            JsonNode root = json.readTree(response.body());
            BigDecimal executedQuantity = decimal(root.path("executed_volume"));
            return new OrderStatus(root.path("uuid").asText(orderId),
                    root.path("state").asText("unknown"), executedQuantity,
                    averageExecutedPrice(root, executedQuantity));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("주문 조회가 중단되었습니다.");
        } catch (Exception error) {
            throw new IllegalStateException("주문 조회 실패: " + error.getMessage(), error);
        }
    }

    private OrderResult placeJwtOrder(String baseUrl, String exchange, String algorithm, ConnectionSecrets secrets,
                                      String market, String side, BigDecimal volume, BigDecimal price) {
        boolean bithumb = "BITHUMB".equals(exchange);
        String clientOrderId = UUID.randomUUID().toString().replace("-", "");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("market", market);
        params.put("side", side);
        params.put(bithumb ? "order_type" : "ord_type", "bid".equals(side) ? "price" : "market");
        if (volume != null) params.put("volume", volume.stripTrailingZeros().toPlainString());
        if (price != null) params.put("price", price.setScale(0, RoundingMode.DOWN).toPlainString());
        params.put(bithumb ? "client_order_id" : "identifier", clientOrderId);
        String query = query(params);
        try {
            String path = bithumb ? "/v2/orders" : "/v1/orders";
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + jwt(secrets.accessKey(), secrets.secretKey(), algorithm, query))
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(params)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.error("Live order failed | exchange={} status={} body={}", exchange, response.statusCode(), response.body());
                throw new IllegalStateException("거래소 주문 실패 HTTP " + response.statusCode());
            }
            JsonNode root = json.readTree(response.body());
            String orderId = root.path(bithumb ? "order_id" : "uuid").asText();
            if (orderId.isBlank()) throw new IllegalStateException("거래소 응답에 주문 ID가 없습니다.");
            log.warn("Live order submitted | exchange={} market={} side={} uuid={}",
                    exchange, market, side, orderId);
            return new OrderResult(orderId, exchange, symbolFromMarket(market),
                    decimal(root.path("volume")), decimal(root.path("price")), root.path("state").asText("wait"));
        } catch (HttpTimeoutException error) {
            OrderResult recovered = recoverByClientOrderId(baseUrl, exchange, algorithm, secrets,
                    market, clientOrderId);
            if (recovered != null) return recovered;
            throw new IllegalStateException("주문 요청 타임아웃 후 식별자 조회에서도 주문을 확인하지 못했습니다.", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("주문 요청이 중단되었습니다.");
        } catch (Exception error) {
            throw new IllegalStateException("주문 요청 실패: " + error.getMessage(), error);
        }
    }

    private OrderResult recoverByClientOrderId(String baseUrl, String exchange, String algorithm,
                                               ConnectionSecrets secrets, String market,
                                               String clientOrderId) {
        boolean bithumb = "BITHUMB".equals(exchange);
        String key = bithumb ? "client_order_id" : "identifier";
        String query = key + "=" + encode(clientOrderId);
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/order?" + query))
                        .timeout(Duration.ofSeconds(5))
                        .header("Authorization", "Bearer " + jwt(
                                secrets.accessKey(), secrets.secretKey(), algorithm, query))
                        .GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    JsonNode root = json.readTree(response.body());
                    String orderId = root.path(bithumb ? "order_id" : "uuid").asText();
                    if (orderId.isBlank()) orderId = root.path("uuid").asText();
                    if (!orderId.isBlank()) {
                        log.warn("Timed out order recovered | exchange={} clientOrderId={} orderId={}",
                                exchange, clientOrderId, orderId);
                        return new OrderResult(orderId, exchange, symbolFromMarket(market),
                                decimal(root.path("executed_volume")),
                                averageExecutedPrice(root, decimal(root.path("executed_volume"))),
                                root.path("state").asText("wait"));
                    }
                }
                Thread.sleep(250L * attempt);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception error) {
                log.warn("Timed out order recovery attempt failed | exchange={} attempt={}", exchange, attempt);
            }
        }
        return null;
    }

    private void guardAmount(BigDecimal krwAmount) {
        if (krwAmount == null) throw new IllegalArgumentException("주문 금액이 필요합니다.");
        if (krwAmount.compareTo(BigDecimal.valueOf(properties.minOrderKrw())) < 0)
            throw new IllegalArgumentException("최소 주문 금액보다 작습니다.");
        if (krwAmount.compareTo(BigDecimal.valueOf(properties.maxOrderKrw())) > 0)
            throw new IllegalArgumentException("최대 주문 금액보다 큽니다.");
    }

    static boolean sellConstraintsSatisfied(BigDecimal quantity, BigDecimal estimatedTotalKrw,
                                            BigDecimal minTotalKrw, BigDecimal availableQuantity) {
        return quantity != null && quantity.signum() > 0
                && estimatedTotalKrw != null && minTotalKrw != null && availableQuantity != null
                && estimatedTotalKrw.compareTo(minTotalKrw) >= 0
                && availableQuantity.compareTo(quantity) >= 0;
    }

    private ConnectionSecrets secrets(String username, Exchange exchange) {
        ExchangeConnectionEntity connection = connections.findByUserUsernameAndExchange(username, exchange)
                .filter(value -> value.getStatus() == ExchangeConnectionEntity.Status.VERIFIED)
                .orElseThrow(() -> new IllegalStateException(exchange + " API 키가 연결 확인 상태가 아닙니다."));
        return new ConnectionSecrets(encryption.decrypt(connection.getEncryptedAccessKey()),
                encryption.decrypt(connection.getEncryptedSecretKey()));
    }

    private String jwt(String accessKey, String secretKey, String algorithm, String query) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("access_key", accessKey.trim());
        payload.put("nonce", UUID.randomUUID().toString());
        if ("HmacSHA256".equals(algorithm)) payload.put("timestamp", System.currentTimeMillis());
        if (query != null && !query.isBlank()) {
            payload.put("query_hash", hex(MessageDigest.getInstance("SHA-512")
                    .digest(query.getBytes(StandardCharsets.UTF_8))));
            payload.put("query_hash_alg", "SHA512");
        }
        String alg = "HmacSHA512".equals(algorithm) ? "HS512" : "HS256";
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(json.writeValueAsBytes(Map.of("alg", alg, "typ", "JWT")));
        String body = encoder.encodeToString(json.writeValueAsBytes(payload));
        String unsigned = header + "." + body;
        return unsigned + "." + encoder.encodeToString(hmac(unsigned.getBytes(StandardCharsets.UTF_8), secretKey, algorithm));
    }

    private static String market(Exchange exchange, String symbol) {
        String base = symbol.replace("/KRW", "").toUpperCase(Locale.ROOT);
        return switch (exchange) {
            case UPBIT -> "KRW-" + base;
            case BITHUMB -> "KRW-" + base;
            case COINONE, KORBIT -> base + "_KRW";
        };
    }

    private static String symbolFromMarket(String market) {
        if (market.startsWith("KRW-")) return market.substring(4) + "/KRW";
        return market.replace("_KRW", "/KRW");
    }

    private static Exchange parse(String value) {
        return Exchange.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private static String query(Map<String, String> params) {
        return params.entrySet().stream()
                .map(value -> encode(value.getKey()) + "=" + encode(value.getValue()))
                .reduce((left, right) -> left + "&" + right).orElse("");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static BigDecimal decimal(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return BigDecimal.ZERO;
        try { return new BigDecimal(value.asText("0")); }
        catch (NumberFormatException error) { return BigDecimal.ZERO; }
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() <= 300 ? value : value.substring(0, 300);
    }

    private static Double percentRate(JsonNode value) {
        if(value==null||value.isMissingNode()||value.isNull())return null;
        double rate=value.asDouble(Double.NaN);
        return Double.isFinite(rate)&&rate>=0?rate*100.0:null;
    }

    private static BigDecimal averageExecutedPrice(JsonNode root, BigDecimal executedQuantity) {
        if (executedQuantity.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        BigDecimal funds = BigDecimal.ZERO;
        JsonNode trades = root.path("trades");
        if (trades.isArray()) {
            for (JsonNode trade : trades) {
                BigDecimal tradeFunds = decimal(trade.path("funds"));
                if (tradeFunds.compareTo(BigDecimal.ZERO) <= 0) {
                    tradeFunds = decimal(trade.path("price")).multiply(decimal(trade.path("volume")));
                }
                funds = funds.add(tradeFunds);
            }
        }
        if (funds.compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal price = decimal(root.path("price"));
            return price.compareTo(BigDecimal.ZERO) > 0 ? price : BigDecimal.ZERO;
        }
        return funds.divide(executedQuantity, 8, RoundingMode.HALF_UP);
    }

    private static byte[] hmac(byte[] value, String secret, String algorithm) throws Exception {
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
        return mac.doFinal(value);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private record ConnectionSecrets(String accessKey, String secretKey) { }
}
