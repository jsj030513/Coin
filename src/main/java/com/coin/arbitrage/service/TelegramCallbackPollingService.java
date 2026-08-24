package com.coin.arbitrage.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
    private final ObjectMapper json;
    private final boolean enabled;
    private final String botToken;

    public TelegramCallbackPollingService(TelegramTradeApprovalService approvals,
                                          ExternalFeeService externalFees,
                                          PrincipalDepositService principalDeposits,
                                          TelegramNotificationService telegram, PortfolioOnboardingService onboarding, ObjectMapper json,
                                          @Value("${telegram.enabled:false}") boolean enabled,
                                          @Value("${telegram.bot-token:}") String botToken) {
        this.approvals = approvals;
        this.externalFees = externalFees;
        this.principalDeposits = principalDeposits;
        this.telegram = telegram;
        this.onboarding = onboarding;
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
        handleDepositMessage(updateId, message, text);
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

    private void handleDepositMessage(long updateId, JsonNode message, String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(?:입금|원금|/deposit)\\s+([0-9,]+)\\s*원?$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (!matcher.matches()) return;
        String chatId = message.path("chat").path("id").asText("");
        try {
            long amount = Long.parseLong(matcher.group(1).replace(",", ""));
            PrincipalDepositService.RecordResult result = principalDeposits.record(chatId, updateId, amount);
            telegram.sendPrincipalDepositRecorded(result.username(), amount, result.todayTotalKrw(), result.totalKrw());
        } catch (RuntimeException error) {
            telegram.sendPrincipalDepositError(chatId, error.getMessage());
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
