package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.ExchangeConnectionEntity;
import com.coin.arbitrage.persistence.ExchangeConnectionEntity.Exchange;
import com.coin.arbitrage.persistence.ExchangeConnectionRepository;
import com.coin.arbitrage.persistence.OpportunityEntity;
import com.coin.arbitrage.persistence.OpportunityRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LiveBalanceService {
    private final ExchangeConnectionRepository connections;
    private final OpportunityRepository opportunities;
    private final CredentialEncryptionService encryption;
    private final ObjectMapper json;
    private final double rebalanceMaxSingleExchangeKrwRatioPercent;
    private final double rebalanceTargetSingleExchangeKrwRatioPercent;
    private final long rebalanceMinTransferKrw;
    private final long rebalanceTransferRoundKrw;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public LiveBalanceService(ExchangeConnectionRepository connections, OpportunityRepository opportunities,
                              CredentialEncryptionService encryption, ObjectMapper json,
                              @Value("${telegram.rebalance-max-single-exchange-krw-ratio-percent:75}") double rebalanceMaxSingleExchangeKrwRatioPercent,
                              @Value("${telegram.rebalance-target-single-exchange-krw-ratio-percent:60}") double rebalanceTargetSingleExchangeKrwRatioPercent,
                              @Value("${telegram.rebalance-min-transfer-krw:10000}") long rebalanceMinTransferKrw,
                              @Value("${telegram.rebalance-transfer-round-krw:1000}") long rebalanceTransferRoundKrw) {
        this.connections = connections;
        this.opportunities = opportunities;
        this.encryption = encryption;
        this.json = json;
        this.rebalanceMaxSingleExchangeKrwRatioPercent = rebalanceMaxSingleExchangeKrwRatioPercent;
        this.rebalanceTargetSingleExchangeKrwRatioPercent = Math.min(
                rebalanceMaxSingleExchangeKrwRatioPercent,
                Math.max(50.0, rebalanceTargetSingleExchangeKrwRatioPercent));
        this.rebalanceMinTransferKrw = rebalanceMinTransferKrw;
        this.rebalanceTransferRoundKrw = Math.max(1, rebalanceTransferRoundKrw);
    }

    public LiveBalanceResponse snapshot(String username) {
        List<LiveAssetBalance> balances = new ArrayList<>();
        List<ExchangeBalanceStatus> statuses = new ArrayList<>();

        for (Exchange exchange : List.of(Exchange.UPBIT, Exchange.BITHUMB)) {
            connections.findByUserUsernameAndExchange(username, exchange)
                    .filter(value -> value.getStatus() == ExchangeConnectionEntity.Status.VERIFIED)
                    .ifPresentOrElse(connection -> {
                        try {
                            List<LiveAssetBalance> fetched = fetch(exchange,
                                    encryption.decrypt(connection.getEncryptedAccessKey()),
                                    encryption.decrypt(connection.getEncryptedSecretKey()));
                            balances.addAll(fetched);
                            statuses.add(new ExchangeBalanceStatus(exchange.name(), true, null, fetched.size()));
                        } catch (Exception error) {
                            statuses.add(new ExchangeBalanceStatus(exchange.name(), false,
                                    "실제 잔고 조회 실패: API 권한, 허용 IP 또는 네트워크를 확인해 주세요.", 0));
                        }
                    }, () -> statuses.add(new ExchangeBalanceStatus(exchange.name(), false,
                            "연결 확인 완료된 조회 전용 API 키가 없습니다.", 0)));
        }

        return new LiveBalanceResponse(Instant.now(),
                statuses, balances, readiness(balances), rebalance(balances));
    }

    private List<LiveAssetBalance> fetch(Exchange exchange, String accessKey, String secretKey) throws Exception {
        return switch (exchange) {
            case UPBIT -> fetchJwtAccounts("https://api.upbit.com/v1/accounts", exchange.name(),
                    accessKey, secretKey, "HmacSHA512");
            case BITHUMB -> fetchJwtAccounts("https://api.bithumb.com/v1/accounts", exchange.name(),
                    accessKey, secretKey, "HmacSHA256");
            case COINONE, KORBIT -> List.of();
        };
    }

    private List<LiveAssetBalance> fetchJwtAccounts(String url, String exchange, String accessKey,
                                                    String secretKey, String algorithm) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("access_key", accessKey.trim());
        payload.put("nonce", UUID.randomUUID().toString());
        if ("HmacSHA256".equals(algorithm)) payload.put("timestamp", System.currentTimeMillis());
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + jwt(payload, secretKey, algorithm))
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
        JsonNode root = json.readTree(response.body());
        if (!root.isArray()) throw new IllegalStateException("Unexpected account response");

        List<LiveAssetBalance> result = new ArrayList<>();
        for (JsonNode item : root) {
            String asset = item.path("currency").asText("").toUpperCase(Locale.ROOT);
            if (asset.isBlank()) continue;
            BigDecimal free = decimal(item.path("balance"));
            BigDecimal locked = decimal(item.path("locked"));
            BigDecimal total = free.add(locked);
            if (total.compareTo(BigDecimal.ZERO) <= 0) continue;
            result.add(new LiveAssetBalance(exchange, asset, free, locked, total,
                    decimal(item.path("avg_buy_price"))));
        }
        result.sort(Comparator.comparing(LiveAssetBalance::exchange).thenComparing(LiveAssetBalance::asset));
        return result;
    }

    private List<LiveOpportunityReadiness> readiness(List<LiveAssetBalance> balances) {
        Map<String, BigDecimal> amountByExchangeAsset = new HashMap<>();
        for (LiveAssetBalance balance : balances) {
            amountByExchangeAsset.put(balance.exchange() + ":" + balance.asset(), balance.free());
        }

        return opportunities.findTop100ByOrderByDetectedAtDesc().stream()
                .filter(value -> List.of("upbit", "bithumb").contains(value.getBuyExchange().toLowerCase(Locale.ROOT)))
                .filter(value -> List.of("upbit", "bithumb").contains(value.getSellExchange().toLowerCase(Locale.ROOT)))
                .map(value -> {
                    String asset = value.getSymbol().replace("/KRW", "").toUpperCase(Locale.ROOT);
                    String buyExchange = value.getBuyExchange().toUpperCase(Locale.ROOT);
                    String sellExchange = value.getSellExchange().toUpperCase(Locale.ROOT);
                    BigDecimal availableKrw = amountByExchangeAsset.getOrDefault(buyExchange + ":KRW", BigDecimal.ZERO);
                    BigDecimal availableBase = amountByExchangeAsset.getOrDefault(sellExchange + ":" + asset, BigDecimal.ZERO);
                    BigDecimal requiredKrw = BigDecimal.valueOf(value.getInvestmentKrw());
                    BigDecimal requiredBase = BigDecimal.valueOf(value.getBaseAmount());
                    boolean enoughKrw = availableKrw.compareTo(requiredKrw) >= 0;
                    boolean enoughBase = availableBase.compareTo(requiredBase) >= 0;
                    String reason = enoughKrw && enoughBase ? "실제 잔고 기준 실행 가능"
                            : (!enoughKrw && !enoughBase ? "매수 KRW와 매도 코인 부족"
                            : (!enoughKrw ? "매수 거래소 KRW 부족" : "매도 거래소 코인 부족"));
                    return new LiveOpportunityReadiness(value.getSymbol(), buyExchange, sellExchange,
                            requiredKrw, requiredBase, availableKrw, availableBase,
                            value.getExpectedProfitKrw(), value.getNetProfitPercent(),
                            enoughKrw && enoughBase, reason, value.getDetectedAt());
                })
                .limit(30)
                .toList();
    }

    private KrwRebalanceRecommendation rebalance(List<LiveAssetBalance> balances) {
        long upbitKrw = krw(balances, "UPBIT");
        long bithumbKrw = krw(balances, "BITHUMB");
        long totalKrw = upbitKrw + bithumbKrw;
        if (totalKrw <= 0) {
            return new KrwRebalanceRecommendation(false, false, "UPBIT", "BITHUMB",
                    "케이뱅크", "KB국민은행", 0, upbitKrw, bithumbKrw, totalKrw,
                    0, 0, rebalanceMaxSingleExchangeKrwRatioPercent, rebalanceMinTransferKrw,
                    rebalanceTargetSingleExchangeKrwRatioPercent,
                    "실제 KRW 잔고가 없거나 조회되지 않았습니다.");
        }

        double upbitRatio = (double) upbitKrw / totalKrw * 100.0;
        double bithumbRatio = (double) bithumbKrw / totalKrw * 100.0;
        String from = upbitKrw >= bithumbKrw ? "UPBIT" : "BITHUMB";
        String to = from.equals("UPBIT") ? "BITHUMB" : "UPBIT";
        long fromKrw = from.equals("UPBIT") ? upbitKrw : bithumbKrw;
        long toKrw = from.equals("UPBIT") ? bithumbKrw : upbitKrw;
        double fromRatio = from.equals("UPBIT") ? upbitRatio : bithumbRatio;
        long transfer = roundDown(fromKrw - targetMaxKrw(totalKrw));
        boolean ratioExceeded = fromRatio > rebalanceMaxSingleExchangeKrwRatioPercent;
        boolean alertEligible = ratioExceeded && transfer >= rebalanceMinTransferKrw;
        String message = alertEligible
                ? "%s에서 %s로 %,d원 이동 권장".formatted(from, to, transfer)
                : ratioExceeded
                ? "쏠림은 있지만 목표 비율까지 낮추는 금액이 최소 알림 기준보다 작습니다."
                : "KRW 비중이 기준 범위 안에 있습니다.";

        return new KrwRebalanceRecommendation(alertEligible, ratioExceeded, from, to,
                bank(from), bank(to), transfer, upbitKrw, bithumbKrw, totalKrw,
                upbitRatio, bithumbRatio, rebalanceMaxSingleExchangeKrwRatioPercent,
                rebalanceMinTransferKrw, rebalanceTargetSingleExchangeKrwRatioPercent, message);
    }

    private long krw(List<LiveAssetBalance> balances, String exchange) {
        return balances.stream()
                .filter(value -> exchange.equals(value.exchange()))
                .filter(value -> "KRW".equals(value.asset()))
                .map(LiveAssetBalance::free)
                .findFirst()
                .orElse(BigDecimal.ZERO)
                .setScale(0, RoundingMode.DOWN)
                .longValue();
    }

    private long roundDown(long amount) {
        return amount <= 0 ? 0 : amount / rebalanceTransferRoundKrw * rebalanceTransferRoundKrw;
    }

    private long targetMaxKrw(long totalKrw) {
        return BigDecimal.valueOf(totalKrw)
                .multiply(BigDecimal.valueOf(rebalanceTargetSingleExchangeKrwRatioPercent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN)
                .longValue();
    }

    private static String bank(String exchange) {
        return "UPBIT".equals(exchange) ? "케이뱅크" : "KB국민은행";
    }

    private static BigDecimal decimal(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return BigDecimal.ZERO;
        try { return new BigDecimal(value.asText("0")); }
        catch (NumberFormatException error) { return BigDecimal.ZERO; }
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

    public record LiveBalanceResponse(Instant checkedAt, List<ExchangeBalanceStatus> statuses,
                                      List<LiveAssetBalance> balances,
                                      List<LiveOpportunityReadiness> readiness,
                                      KrwRebalanceRecommendation rebalance) { }

    public record ExchangeBalanceStatus(String exchange, boolean connected, String message, int assetCount) { }

    public record LiveAssetBalance(String exchange, String asset, BigDecimal free, BigDecimal locked,
                                   BigDecimal total, BigDecimal avgBuyPrice) { }

    public record LiveOpportunityReadiness(String symbol, String buyExchange, String sellExchange,
                                           BigDecimal requiredKrw, BigDecimal requiredBase,
                                           BigDecimal availableKrw, BigDecimal availableBase,
                                           double expectedProfitKrw, double netProfitPercent,
                                           boolean executable, String reason, Instant detectedAt) { }

    public record KrwRebalanceRecommendation(boolean alertEligible, boolean ratioExceeded,
                                             String fromExchange, String toExchange,
                                             String fromBank, String toBank, long transferKrw,
                                             long upbitKrw, long bithumbKrw, long totalKrw,
                                             double upbitRatioPercent, double bithumbRatioPercent,
                                             double maxSingleExchangeRatioPercent,
                                             long minTransferKrw,
                                             double targetSingleExchangeRatioPercent,
                                             String message) { }
}
