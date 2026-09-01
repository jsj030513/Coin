package com.coin.arbitrage.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TelegramCallbackPollingService {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicLong offset = new AtomicLong();
    private final TelegramTradeApprovalService approvals;
    private final ExternalFeeService externalFees;
    private final PrincipalDepositService principalDeposits;
    private final TelegramNotificationService telegram;
    private final PortfolioOnboardingService onboarding;
    private final NotificationSettingsService notificationSettings;
    private final DelistingRiskService delistingRisk;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String botToken;

    public TelegramCallbackPollingService(TelegramTradeApprovalService approvals,
                                          ExternalFeeService externalFees,
                                          PrincipalDepositService principalDeposits,
                                          TelegramNotificationService telegram, PortfolioOnboardingService onboarding,
                                          NotificationSettingsService notificationSettings,
                                          DelistingRiskService delistingRisk, ObjectMapper json,
                                          @Value("${telegram.enabled:false}") boolean enabled,
                                          @Value("${telegram.bot-token:}") String botToken) {
        this.approvals = approvals;
        this.externalFees = externalFees;
        this.principalDeposits = principalDeposits;
        this.telegram = telegram;
        this.onboarding = onboarding;
        this.notificationSettings = notificationSettings;
        this.delistingRisk = delistingRisk;
        this.json = json;
        this.enabled = enabled;
        this.botToken = botToken == null ? "" : botToken.trim();
    }

    @Scheduled(fixedDelayString = "${telegram.approval-poll-interval-ms:2000}")
    public void poll() {
        if (!enabled || botToken.isBlank()) return;
        try {
            URI uri = URI.create(api("getUpdates") + "?offset=" + offset.get()
                    + "&timeout=0&allowed_updates=" + encode("[\"callback_query\",\"message\"]"));
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return;
            JsonNode results = json.readTree(response.body()).path("result");
            if (!results.isArray()) return;
            for (JsonNode update : results) {
                offset.accumulateAndGet(update.path("update_id").asLong() + 1, Math::max);
                JsonNode callback = update.path("callback_query");
                JsonNode messageNode = update.path("message");
                if (!messageNode.isMissingNode()) {
                    handleTextMessage(update.path("update_id").asLong(), messageNode);
                    continue;
                }
                String data = callback.path("data").asText("");
                if (data.startsWith("onboard:")) {
                    String[] parts=data.split(":",3);
                    String chatId=callback.path("message").path("chat").path("id").asText("");
                    String message=parts.length==3?onboarding.handleCallback(parts[2],parts[1],chatId):"잘못된 요청입니다.";
                    answer(callback.path("id").asText(""),message);
                    continue;
                }
                if (!data.startsWith("trade:approve:") && !data.startsWith("trade:reject:")) continue;
                boolean approve = data.startsWith("trade:approve:");
                String token = data.substring(data.lastIndexOf(':') + 1);
                String chatId = callback.path("message").path("chat").path("id").asText("");
                String message = approvals.handleCallback(token, approve, chatId);
                answer(callback.path("id").asText(""), message);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // 다음 주기에 재시도한다. 토큰은 만료 전까지 유효하다.
        }
    }

    private void handleTextMessage(long updateId, JsonNode message) {
        String text = message.path("text").asText("").trim();
        if (handleFeeMessage(updateId, message, text)) return;
        if (handleDepositMessage(updateId, message, text)) return;
        handleRiskLiquidationMessage(message, text);
    }

    private boolean handleFeeMessage(long updateId, JsonNode message, String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(?:수수료|/fee)\\s+([0-9,]+)\\s*원?$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (!matcher.matches()) return false;
        String chatId = message.path("chat").path("id").asText("");
        try {
            long amount = Long.parseLong(matcher.group(1).replace(",", ""));
            ExternalFeeService.RecordResult result = externalFees.record(chatId, updateId, amount);
            telegram.sendExternalFeeRecorded(result.username(), amount, result.todayTotalKrw());
        } catch (RuntimeException error) {
            telegram.sendExternalFeeError(chatId, error.getMessage());
        }
        return true;
    }

    private boolean handleDepositMessage(long updateId, JsonNode message, String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(?:입금|원금|/deposit)\\s+([0-9,]+)\\s*원?$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (!matcher.matches()) return false;
        String chatId = message.path("chat").path("id").asText("");
        try {
            long amount = Long.parseLong(matcher.group(1).replace(",", ""));
            PrincipalDepositService.RecordResult result = principalDeposits.record(chatId, updateId, amount);
            telegram.sendPrincipalDepositRecorded(result.username(), amount, result.todayTotalKrw(), result.totalKrw());
        } catch (RuntimeException error) {
            telegram.sendPrincipalDepositError(chatId, error.getMessage());
        }
        return true;
    }

    private void handleRiskLiquidationMessage(JsonNode message, String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^([A-Za-z0-9]{2,12})(?:/KRW)?\\s*정리$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (!matcher.matches()) return;
        String chatId = message.path("chat").path("id").asText("");
        String symbol = matcher.group(1).trim().toUpperCase(Locale.ROOT) + "/KRW";
        if (!delistingRisk.risky("UPBIT", symbol) && !delistingRisk.risky("BITHUMB", symbol)) {
            telegram.sendTelegramCommandResult(chatId, "위험 코인 정리 보류",
                    symbol + "은(는) 현재 거래정지 위험 목록에 없습니다.\n"
                            + "오입력 방지를 위해 텔레그램 정리 명령은 위험 목록 코인에만 동작합니다.");
            return;
        }
        try {
            String username = notificationSettings.usernameByTelegramChatId(chatId)
                    .orElseThrow(() -> new IllegalStateException("텔레그램 알림이 연결된 계정을 찾을 수 없습니다."));
            approvals.requestLiquidation(username, Set.of("UPBIT|" + symbol, "BITHUMB|" + symbol));
            telegram.sendTelegramCommandResult(chatId, "위험 코인 정리 승인 요청",
                    symbol + " 정리 승인 요청을 보냈습니다.\n"
                            + "승인 버튼을 누르면 해당 코인만 시장가 정리합니다. 5천원 미만이면 보충 매수 후 매도 로직을 적용합니다.");
        } catch (RuntimeException error) {
            telegram.sendTelegramCommandResult(chatId, "위험 코인 정리 오류", error.getMessage());
        }
    }

    private void answer(String callbackId, String message) throws Exception {
        if (callbackId.isBlank()) return;
        String body = "callback_query_id=" + encode(callbackId) + "&text=" + encode(shortText(message));
        http.send(HttpRequest.newBuilder(URI.create(api("answerCallbackQuery")))
                .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
    }

    private String api(String method) { return "https://api.telegram.org/bot" + botToken + "/" + method; }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String shortText(String value) { return value.length() <= 180 ? value : value.substring(0, 180); }
}
