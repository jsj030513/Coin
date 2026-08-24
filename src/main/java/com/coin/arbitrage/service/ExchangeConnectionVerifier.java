package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.ExchangeConnectionEntity.Exchange;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class ExchangeConnectionVerifier {
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public ExchangeConnectionVerifier(ObjectMapper json) { this.json = json; }

    public Verification verify(Exchange exchange, String accessKey, String secretKey) {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank())
            throw new IllegalArgumentException("API Key와 Secret Key를 모두 입력해 주세요.");
        try {
            return switch (exchange) {
                case UPBIT -> verifyJwtBalance("https://api.upbit.com/v1/accounts", accessKey, secretKey, "HmacSHA512");
                case BITHUMB -> verifyJwtBalance("https://api.bithumb.com/v1/accounts", accessKey, secretKey, "HmacSHA256");
                case COINONE -> verifyCoinone(accessKey, secretKey);
                case KORBIT -> verifyKorbit(accessKey, secretKey);
            };
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failed("연결 확인이 중단되었습니다.");
        } catch (Exception error) {
            return failed("인증 또는 네트워크 연결에 실패했습니다.");
        }
    }

    private Verification verifyJwtBalance(String url, String accessKey, String secretKey, String algorithm) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("access_key", accessKey.trim());
        payload.put("nonce", UUID.randomUUID().toString());
        if ("HmacSHA256".equals(algorithm)) payload.put("timestamp", System.currentTimeMillis());
        String token = jwt(payload, secretKey, algorithm);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token).GET().build();
        Verification balance = arrayResponse(http.send(request, HttpResponse.BodyHandlers.ofString()));
        if (!balance.connected()) return balance;
        OrderCapability order = verifyJwtOrderRead(url.substring(0, url.indexOf("/v1/")),
                accessKey, secretKey, algorithm);
        return new Verification(true, balance.assetCount(), null, order.permission(), PermissionCheck.UNKNOWN,
                order.buyFeePercent(), order.sellFeePercent());
    }

    private OrderCapability verifyJwtOrderRead(String baseUrl, String accessKey, String secretKey,
                                               String algorithm) {
        try {
            String query = "market=KRW-BTC";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("access_key", accessKey.trim());
            payload.put("nonce", UUID.randomUUID().toString());
            if ("HmacSHA256".equals(algorithm)) payload.put("timestamp", System.currentTimeMillis());
            payload.put("query_hash", hex(MessageDigest.getInstance("SHA-512")
                    .digest(query.getBytes(StandardCharsets.UTF_8))));
            payload.put("query_hash_alg", "SHA512");
            String token = jwt(payload, secretKey, algorithm);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/orders/chance?" + query))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status / 100 == 2) {
                JsonNode root = json.readTree(response.body());
                return new OrderCapability(PermissionCheck.VERIFIED,
                        percentRate(root.path("bid_fee")), percentRate(root.path("ask_fee")));
            }
            if (status == 401 || status == 403)
                return new OrderCapability(PermissionCheck.NOT_GRANTED, null, null);
        } catch (Exception ignored) { }
        return new OrderCapability(PermissionCheck.UNKNOWN, null, null);
    }

    private Verification verifyCoinone(String accessKey, String secretKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", accessKey.trim());
        body.put("nonce", UUID.randomUUID().toString());
        HttpResponse<String> response = coinoneRequest("https://api.coinone.co.kr/v2.1/account/balance/all",
                body, secretKey);
        if (response.statusCode() / 100 != 2) return failedHttp(response.statusCode());
        JsonNode root = json.readTree(response.body());
        if (!"success".equalsIgnoreCase(root.path("result").asText())) return failed("코인원 API 키 인증에 실패했습니다.");
        PermissionCheck orderRead = verifyCoinoneOrderRead(accessKey, secretKey);
        return new Verification(true, root.path("balances").size(), null, orderRead, PermissionCheck.UNKNOWN,
                null, null);
    }

    private PermissionCheck verifyCoinoneOrderRead(String accessKey, String secretKey) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("access_token", accessKey.trim());
            body.put("nonce", UUID.randomUUID().toString());
            body.put("quote_currency", "KRW");
            body.put("target_currency", "BTC");
            HttpResponse<String> response = coinoneRequest(
                    "https://api.coinone.co.kr/v2.1/order/active_orders", body, secretKey);
            if (response.statusCode() / 100 == 2) {
                JsonNode root = json.readTree(response.body());
                return "success".equalsIgnoreCase(root.path("result").asText())
                        ? PermissionCheck.VERIFIED : PermissionCheck.NOT_GRANTED;
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) return PermissionCheck.NOT_GRANTED;
        } catch (Exception ignored) { }
        return PermissionCheck.UNKNOWN;
    }

    private HttpResponse<String> coinoneRequest(String url, Map<String, Object> bodyValues,
                                                String secretKey) throws Exception {
        String body = json.writeValueAsString(bodyValues);
        String payload = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        String signature = hex(hmac(payload.getBytes(StandardCharsets.UTF_8), secretKey, "HmacSHA512"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("X-COINONE-PAYLOAD", payload)
                .header("X-COINONE-SIGNATURE", signature).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Verification verifyKorbit(String accessKey, String secretKey) throws Exception {
        String query = "timestamp=" + System.currentTimeMillis();
        String signature = hex(hmac(query.getBytes(StandardCharsets.UTF_8), secretKey, "HmacSHA256"));
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.korbit.co.kr/v2/balance?" + query + "&signature=" + signature))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json")
                .header("X-KAPI-KEY", accessKey.trim()).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) return failedHttp(response.statusCode());
        JsonNode root = json.readTree(response.body());
        if (!root.path("success").asBoolean(false)) return failed("코빗 API 키 인증에 실패했습니다.");
        JsonNode data = root.path("data");
        PermissionCheck[] permissions = verifyKorbitPermissions(accessKey, secretKey);
        return new Verification(true, data.isArray() ? data.size() : 0, null, permissions[0], permissions[1],
                null, null);
    }

    private PermissionCheck[] verifyKorbitPermissions(String accessKey, String secretKey) {
        try {
            String query = "timestamp=" + System.currentTimeMillis();
            String signature = hex(hmac(query.getBytes(StandardCharsets.UTF_8), secretKey, "HmacSHA256"));
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "https://api.korbit.co.kr/v2/currentKeyInfo?" + query + "&signature=" + signature))
                    .timeout(Duration.ofSeconds(10)).header("X-KAPI-KEY", accessKey.trim()).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                JsonNode permissions = json.readTree(response.body()).path("data").path("permissions");
                boolean read = contains(permissions, "readOrders");
                boolean write = contains(permissions, "writeOrders");
                return new PermissionCheck[]{read ? PermissionCheck.VERIFIED : PermissionCheck.NOT_GRANTED,
                        write ? PermissionCheck.VERIFIED : PermissionCheck.NOT_GRANTED};
            }
        } catch (Exception ignored) { }
        return new PermissionCheck[]{PermissionCheck.UNKNOWN, PermissionCheck.UNKNOWN};
    }

    private static boolean contains(JsonNode array, String expected) {
        if (!array.isArray()) return false;
        for (JsonNode value : array) if (expected.equals(value.asText())) return true;
        return false;
    }

    private Verification arrayResponse(HttpResponse<String> response) throws Exception {
        if (response.statusCode() / 100 != 2) return failedHttp(response.statusCode());
        JsonNode root = json.readTree(response.body());
        if (!root.isArray()) return failed("거래소가 예상하지 못한 응답을 반환했습니다.");
        return new Verification(true, root.size(), null, PermissionCheck.UNKNOWN, PermissionCheck.UNKNOWN,
                null, null);
    }

    private Verification failedHttp(int status) {
        return failed(status == 401 || status == 403
                ? "API 키, 조회 권한 또는 허용 IP를 확인해 주세요." : "거래소 응답 오류(HTTP " + status + ")");
    }

    private static Verification failed(String message) {
        return new Verification(false, 0, message, PermissionCheck.UNKNOWN, PermissionCheck.UNKNOWN,
                null, null);
    }

    private static Double percentRate(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        double decimalRate = value.asDouble(Double.NaN);
        if (!Double.isFinite(decimalRate) || decimalRate < 0) return null;
        return decimalRate * 100.0;
    }

    private String jwt(Map<String, Object> payload, String secret, String algorithm) throws Exception {
        String alg = "HmacSHA512".equals(algorithm) ? "HS512" : "HS256";
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(json.writeValueAsBytes(Map.of("alg", alg, "typ", "JWT")));
        String body = encoder.encodeToString(json.writeValueAsBytes(payload));
        String unsigned = header + "." + body;
        return unsigned + "." + encoder.encodeToString(hmac(unsigned.getBytes(StandardCharsets.UTF_8), secret, algorithm));
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

    public enum PermissionCheck { VERIFIED, NOT_GRANTED, UNKNOWN }
    private record OrderCapability(PermissionCheck permission, Double buyFeePercent,
                                   Double sellFeePercent) { }
    public record Verification(boolean connected, int assetCount, String message,
                               PermissionCheck orderReadPermission,
                               PermissionCheck orderCreatePermission,
                               Double buyFeePercent, Double sellFeePercent) { }
}
