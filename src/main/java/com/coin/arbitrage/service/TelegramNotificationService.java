package com.coin.arbitrage.service;

import com.coin.arbitrage.domain.ArbitrageOpportunity;
import com.coin.arbitrage.persistence.NotificationSettingsEntity;
import com.coin.arbitrage.persistence.NotificationSettingsRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TelegramNotificationService {
    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Seoul"));

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Instant> lastAlertAt = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastTradeBlockAlertAt = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastFailureAlertAt = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastFeeAlertAt = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedQueue<String>> autoPortfolioSummaries = new ConcurrentHashMap<>();
    private final NotificationSettingsRepository notificationSettings;
    private final NotificationSettingsService settingsService;
    private final boolean enabled;
    private final boolean opportunityEnabled;
    private final String botToken;
    private final double minProfitPercent;
    private final double minExpectedProfitKrw;
    private final int topN;
    private final long cooldownSeconds;
    private final long liveCandidateCooldownMinutes;
    private final long manualKrwTransferFeeKrw;
    private final long manualKrwTransferMinKrw;

    public TelegramNotificationService(NotificationSettingsRepository notificationSettings,
                                       NotificationSettingsService settingsService,
                                       @Value("${telegram.enabled:false}") boolean enabled,
                                       @Value("${telegram.opportunity-enabled:false}") boolean opportunityEnabled,
                                       @Value("${telegram.bot-token:}") String botToken,
                                       @Value("${telegram.min-profit-percent:0.3}") double minProfitPercent,
                                       @Value("${telegram.min-expected-profit-krw:100}") double minExpectedProfitKrw,
                                       @Value("${telegram.top-n:3}") int topN,
                                       @Value("${telegram.cooldown-seconds:3600}") long cooldownSeconds,
                                       @Value("${telegram.live-candidate-cooldown-minutes:180}") long liveCandidateCooldownMinutes,
                                       @Value("${live-trading.manual-krw-transfer-fee-krw:1000}") long manualKrwTransferFeeKrw,
                                       @Value("${live-trading.manual-krw-transfer-min-krw:20000}") long manualKrwTransferMinKrw) {
        this.notificationSettings = notificationSettings;
        this.settingsService = settingsService;
        this.enabled = enabled;
        this.opportunityEnabled = opportunityEnabled;
        this.botToken = botToken == null ? "" : botToken.trim();
        this.minProfitPercent = minProfitPercent;
        this.minExpectedProfitKrw = minExpectedProfitKrw;
        this.topN = Math.max(1, topN);
        this.cooldownSeconds = Math.max(0, cooldownSeconds);
        this.liveCandidateCooldownMinutes = Math.max(30, liveCandidateCooldownMinutes);
        this.manualKrwTransferFeeKrw = Math.max(0, manualKrwTransferFeeKrw);
        this.manualKrwTransferMinKrw = Math.max(0, manualKrwTransferMinKrw);
    }

    public boolean configured() {
        return enabled && !botToken.isBlank();
    }

    public boolean configured(String username) {
        return configured() && settingsService.canSendTelegram(username, NotificationSettingsService.Channel.TEST);
    }

    public void notifyOpportunities(List<ArbitrageOpportunity> opportunities) {
        if (!configured() || !opportunityEnabled || opportunities == null || opportunities.isEmpty()) return;

        List<ArbitrageOpportunity> targets = opportunities.stream()
                .filter(value -> value.netProfitPercent() >= minProfitPercent)
                .filter(value -> value.expectedProfitKrw() >= minExpectedProfitKrw)
                .sorted(Comparator.comparingDouble(ArbitrageOpportunity::netProfitPercent).reversed())
                .limit(topN)
                .toList();

        if (targets.isEmpty()) return;
        List<NotificationSettingsEntity> recipients = notificationSettings.findByTelegramEnabledTrueAndOpportunityEnabledTrue()
                .stream()
                .filter(value -> !value.getTelegramChatId().isBlank())
                .toList();
        if (recipients.isEmpty()) return;
        CompletableFuture.runAsync(() -> recipients.forEach(recipient ->
                sendOpportunityDigest(recipient.getUser().getUsername(), targets)));
    }

    public void sendTestMessage(String username) {
        if (!configured()) throw new IllegalStateException("Telegram bot token is not configured");
        if (!settingsService.canSendTelegram(username, NotificationSettingsService.Channel.TEST)) {
            throw new IllegalStateException("Telegram notification is not configured for this user");
        }
        send(username, """
                [ARB KOREA]
                텔레그램 알림 테스트 성공

                알림 대상: 현재 로그인 사용자
                """);
    }

    public void sendAccountRecovery(String username, String code, long validMinutes) {
        if (!configured(username)) {
            throw new IllegalStateException("계정에 연결된 텔레그램 알림 설정이 없습니다.");
        }
        CompletableFuture.runAsync(() -> send(username, """
                [ARB KOREA ACCOUNT RECOVERY]

                아이디: %s
                일회용 복구 코드: %s
                유효시간: %d분

                본인이 요청하지 않았다면 이 코드를 입력하지 마세요.
                거래소 API 키나 비밀번호를 텔레그램으로 보내지 마세요.
                """.formatted(username, code, validMinutes)));
    }

    public void sendTradeApproval(String username, String token, String title, String detail) {
        if (!configured(username)) throw new IllegalStateException("계정에 연결된 텔레그램 알림 설정이 없습니다.");
        String keyboard = "{\"inline_keyboard\":[[{\"text\":\"승인\",\"callback_data\":\"trade:approve:%s\"},{\"text\":\"거절\",\"callback_data\":\"trade:reject:%s\"}]]}"
                .formatted(token, token);
        CompletableFuture.runAsync(() -> send(username, """
                [TRADE APPROVAL]

                %s

                %s

                유효시간: 10분
                승인 전 거래소 잔고와 금액을 다시 확인하세요.
                """.formatted(title, detail), keyboard));
    }

    public void sendTradeApprovalResult(String username, String message) {
        if (!configured(username)) return;
        CompletableFuture.runAsync(() -> send(username, """
                [TRADE APPROVAL RESULT]

                %s
                """.formatted(message)));
    }

    public void sendPortfolioCompatibility(String username, String token, String detail) {
        if (!configured(username)) return;
        String keyboard = "{\"inline_keyboard\":[[{\"text\":\"기존 코인 유지\",\"callback_data\":\"onboard:keep:%s\"},{\"text\":\"정리 후 계속\",\"callback_data\":\"onboard:sell:%s\"}]]}"
                .formatted(token, token);
        CompletableFuture.runAsync(() -> send(username, """
                [신규 계정 코인 점검]

                양 거래소에서 함께 운용하기 어려운 기존 코인이 있습니다.

                %s

                · 유지: 해당 코인은 건드리지 않고 추천 코인만 안내합니다.
                · 정리 후 계속: 매도 목록을 다시 보여주고 별도 승인을 받습니다.
                """.formatted(detail), keyboard));
    }

    public void sendPortfolioRecommendations(String username, String token, String detail) {
        if (!configured(username)) return;
        String keyboard = "{\"inline_keyboard\":[[{\"text\":\"추천 코인 매수 검토\",\"callback_data\":\"onboard:buy:%s\"}]]}"
                .formatted(token);
        CompletableFuture.runAsync(() -> send(username, """
                [신규 매수 추천]

                최근 차익기회와 양 거래소 지원 여부를 기준으로 계산했습니다.

                %s

                버튼을 누르면 금액과 대상을 다시 계산한 뒤 별도 승인 요청을 보냅니다.
                """.formatted(detail), keyboard));
    }

    public void sendInitialSetupReport(String username, String detail) {
        if (!configured(username)) return;
        CompletableFuture.runAsync(() -> send(username, """
                [초기 세팅 리포트]

                신규 사용자 자동 세팅을 계산했습니다.

                %s
                """.formatted(detail)));
    }

    public void notifyAutoPortfolioOrder(String username, String message) {
        if (!configured(username)) return;
        autoPortfolioSummaries.computeIfAbsent(username, ignored -> new ConcurrentLinkedQueue<>()).add(message);
    }

    public void notifyLiveOrderSubmitted(String username, String side, long requestedKrw,
                                         String source, com.coin.arbitrage.domain.OrderResult result) {
        if (!configured(username)) return;
        CompletableFuture.runAsync(() -> send(username, """
                [실제 주문 완료]

                매수/매도 주문이 거래소에 접수되었습니다.

                구분: %s
                거래소: %s
                코인: %s
                주문 유형: %s
                요청 금액: %,d원
                수량: %s
                체결/주문 상태: %s
                주문번호: %s

                실제 최종 체결 손익은 체결 확인 후 손익 내역에 반영됩니다.
                """.formatted(
                "BUY".equalsIgnoreCase(side) ? "매수" : "매도",
                result.exchange(), result.symbol(), source,
                requestedKrw, result.quantity() == null ? "0" : result.quantity().stripTrailingZeros().toPlainString(),
                result.status(), result.orderId())));
    }

    @Scheduled(
            fixedDelayString = "${telegram.auto-portfolio-summary-interval-ms:300000}",
            initialDelayString = "${telegram.auto-portfolio-summary-initial-delay-ms:60000}")
    public void flushAutoPortfolioSummaries() {
        autoPortfolioSummaries.forEach((username, messages) -> {
            List<String> drained = new java.util.ArrayList<>();
            String message;
            while ((message = messages.poll()) != null) drained.add(message);
            if (drained.isEmpty()) return;
            CompletableFuture.runAsync(() -> send(username, """
                    [자동 작업 요약]

                    최근 자동 작업: %d건

                    %s

                    반복 알림을 줄이기 위해 자동 매수·재고 보충·원화 확보 알림은 묶어서 전송합니다.
                    자동거래 비상 정지는 웹 화면에서 사용할 수 있습니다.
                    """.formatted(drained.size(), drained.stream()
                    .map(value -> "• " + value).collect(java.util.stream.Collectors.joining("\n")))));
        });
    }

    public void notifyKrwRebalance(String username, String fromExchange, String fromBank,
                                   String toExchange, String toBank, long amountKrw,
                                   long fromKrw, long toKrw, long totalKrw,
                                   double fromRatioPercent, String reason) {
        if (!configured() || !settingsService.canSendTelegram(username, NotificationSettingsService.Channel.REBALANCE)) return;
        if (!settingsService.claimKrwRebalanceAlert(username, fromExchange, toExchange)) return;
        CompletableFuture.runAsync(() -> send(username, """
                [원화 이동 필요]

                원화가 한쪽 거래소에 쏠려 있습니다.

                지금 옮길 금액: %,d원
                이동 방향: %s → %s

                이동 순서:
                1. %s에서 %s 계좌로 출금
                2. %s 계좌에서 %s 계좌로 송금
                3. %s 계좌에서 %s로 입금

                현재 KRW:
                %s: %,d원
                %s: %,d원
                합계: %,d원

                쏠림 비중: %.1f%%
                사유: %s

                참고:
                실제 자동 이체/주문은 실행하지 않습니다.
                은행 앱 또는 거래소 입출금 화면에서 직접 확인 후 처리하세요.
                """.formatted(
                amountKrw, fromExchange, toExchange,
                fromExchange, fromBank, fromBank, toBank, toBank, toExchange,
                fromExchange, fromKrw, toExchange, toKrw, totalKrw,
                fromRatioPercent, reason
        )));
    }

    public void notifyAutoTradeBlockedByKrw(String username, String symbol, String buyExchange,
                                            String sellExchange, long neededKrw, long availableKrw,
                                            long sourceExchangeKrw,
                                            double netProfitPercent, double expectedProfitKrw) {
        if (!configured() || !settingsService.canSendTelegram(username, NotificationSettingsService.Channel.LIVE_CANDIDATE)) return;
        String key = username + ":KRW_BLOCK:" + buyExchange;
        Instant now = Instant.now();
        Instant previous = lastTradeBlockAlertAt.get(key);
        if (previous != null && previous.plus(Duration.ofMinutes(liveCandidateCooldownMinutes)).isAfter(now)) return;
        lastTradeBlockAlertAt.put(key, now);
        long shortage = Math.max(0, neededKrw - availableKrw);
        long topUp = roundUp(Math.max(5_000, shortage), 1_000);
        long transferWithFee = topUp + manualKrwTransferFeeKrw;
        String topUpLine = "%s(%s)으로 %,d원 충전 추천".formatted(
                buyExchange, bank(buyExchange), topUp);
        String transferHint;
        if (topUp < manualKrwTransferMinKrw) {
            transferHint = "부족액이 %,d원 미만이라 직접 이동 추천은 보류합니다. 수수료 비중이 커서 자동 원화 확보를 우선합니다."
                    .formatted(manualKrwTransferMinKrw);
        } else if (sourceExchangeKrw >= transferWithFee) {
            transferHint = "반대 거래소 %s에서 직접 옮긴다면 %,d원 이동 추천입니다. 예상 도착 %,d원 · 수수료 %,d원 포함.".formatted(
                    sellExchange, transferWithFee, topUp, manualKrwTransferFeeKrw);
        } else {
            transferHint = "반대 거래소 %s 원화가 이동 수수료 포함 %,d원보다 부족해서 직접 이동 추천은 보류합니다.".formatted(
                    sellExchange, transferWithFee);
        }
        CompletableFuture.runAsync(() -> send(username, """
                [자동거래 대기 중]

                기회는 있지만 매수 거래소 KRW가 부족해서 실제 주문을 보내지 못했습니다.

                KRW 상태:
                %s
                %s

                코인: %s
                경로: %s 매수 → %s 매도
                예상: %.2f원 · %.3f%%

                필요한 KRW: %,d원
                %s 사용 가능 KRW: %,d원
                %s 사용 가능 KRW: %,d원
                부족액 기준 보충 필요액: %,d원
                직접 이동 추천 최소액: %,d원

                거래 데이터를 쌓으려면 %s 쪽에 5천원 이상 주문 가능한 원화가 필요합니다.
                원화 이동 1회 수수료 기준: %,d원
                자동 원화 확보가 더 유리하면 이런 알림 없이 자동으로 진행합니다.
                """.formatted(topUpLine, transferHint, symbol, buyExchange, sellExchange, expectedProfitKrw, netProfitPercent,
                neededKrw, buyExchange, availableKrw, sellExchange, sourceExchangeKrw, topUp, manualKrwTransferMinKrw, buyExchange,
                manualKrwTransferFeeKrw)));
    }

    public void notifyAutoKrwRecovery(String username, String soldSymbol, String exchange,
                                      long estimatedSellKrw, long availableKrwBefore, long neededKrw,
                                      long realizedProfitKrw,
                                      String blockedSymbol, String buyExchange, String sellExchange) {
        if (!configured() || !settingsService.canSendTelegram(username, NotificationSettingsService.Channel.LIVE_CANDIDATE)) return;
        notifyAutoPortfolioOrder(username,
                "%s 원화 자동확보 · %s 매도 · 예상 %,d원 · 정리손익 %,d원 · 막힌 기회 %s %s→%s"
                        .formatted(exchange, soldSymbol, estimatedSellKrw, realizedProfitKrw,
                                blockedSymbol, buyExchange, sellExchange));
    }

    public void notifyManualKrwTransferPreferred(String username, String fromExchange, String fromBank,
                                                 String toExchange, String toBank, long amountKrw,
                                                 long fromKrw, long toKrw, long manualFeeKrw,
                                                 Long autoRecoveryCostKrw,
                                                 String blockedSymbol, double expectedProfitKrw,
                                                 String reason) {
        if (!configured() || !settingsService.canSendTelegram(username, NotificationSettingsService.Channel.REBALANCE)) return;
        String key = username + ":MANUAL_KRW_TRANSFER:" + fromExchange + "->" + toExchange;
        Instant now = Instant.now();
        Instant previous = lastTradeBlockAlertAt.get(key);
        if (previous != null && previous.plus(Duration.ofMinutes(liveCandidateCooldownMinutes)).isAfter(now)) return;
        lastTradeBlockAlertAt.put(key, now);
        String autoCost = autoRecoveryCostKrw == null ? "계산 불가" : "%,d원".formatted(autoRecoveryCostKrw);
        long transferWithFee = amountKrw + manualFeeKrw;
        CompletableFuture.runAsync(() -> send(username, """
                [직접 원화 충전 권장]

                원화 부족을 자동 매도로 해결하는 것보다, 직접 원화를 충전/이동하는 쪽이 더 효율적이라고 판단했습니다.
                자동 원화 확보는 이번에는 실행하지 않습니다.

                충전 추천:
                %s(%s)으로 %,d원 충전

                직접 이동한다면:
                1. %s에서 %s 계좌로 %,d원 출금
                2. %s 계좌에서 %s 계좌로 송금
                3. %s 계좌에서 %s로 입금
                도착 목표: %,d원 · 수수료: %,d원

                비용 비교:
                직접 이동 예상 수수료: %,d원
                자동 원화 확보 예상 비용: %s

                현재 KRW:
                %s: %,d원
                %s: %,d원

                막힌 기회:
                %s · 예상 %.2f원

                판단 사유:
                %s
                """.formatted(toExchange, toBank, amountKrw,
                fromExchange, fromBank, transferWithFee, fromBank, toBank, toBank, toExchange,
                amountKrw, manualFeeKrw,
                manualFeeKrw, autoCost,
                fromExchange, fromKrw, toExchange, toKrw,
                blockedSymbol, expectedProfitKrw, reason)));
    }

    public void clearKrwRebalanceAlert(String username) {
        settingsService.clearKrwRebalanceAlert(username);
    }

    private static long roundUp(long amount, long unit) {
        if (amount <= 0) return 0;
        long safeUnit = Math.max(1, unit);
        return ((amount + safeUnit - 1) / safeUnit) * safeUnit;
    }

    private static String bank(String exchange) {
        return switch (exchange == null ? "" : exchange.trim().toUpperCase()) {
            case "UPBIT" -> "케이뱅크";
            case "BITHUMB" -> "국민은행";
            default -> "연결 은행";
        };
    }

    public void notifyAutoTradingFailure(String username, String symbol, String buyExchange,
                                         String sellExchange, String detail) {
        if (!configured() || !settingsService.canSendTelegram(username, NotificationSettingsService.Channel.TEST)) return;
        String key = username + ":FAILURE:" + symbol + ":" + buyExchange + ":" + sellExchange + ":" + detail;
        Instant now = Instant.now();
        Instant previous = lastFailureAlertAt.get(key);
        if (previous != null && previous.plus(Duration.ofMinutes(30)).isAfter(now)) return;
        lastFailureAlertAt.put(key, now);
        CompletableFuture.runAsync(() -> send(username, """
                [AUTO TRADING STOPPED]

                자동 차익거래를 즉시 껐습니다.
                코인: %s
                경로: %s 매수 → %s 매도
                사유: %s

                거래소 주문 내역과 실제 잔고를 직접 확인해 주세요.
                """.formatted(symbol, buyExchange, sellExchange, detail)));
    }

    public void notifyAdminLoginFailure(String username, String ip) {
        if (!configured(username)) return;
        CompletableFuture.runAsync(() -> send(username, """
                [관리자 로그인 실패]

                관리자 계정 로그인에 실패했습니다.
                접속 IP: %s
                시각: %s

                본인이 아니라면 비밀번호와 2단계 인증 상태를 확인하세요.
                """.formatted(ip, TIME_FORMATTER.format(Instant.now()))));
    }

    public void sendDailyProfitSummary(String username, java.math.BigDecimal todayProfitKrw,
                                       java.math.BigDecimal totalProfitKrw,
                                       java.math.BigDecimal todayExternalFeeKrw,
                                       java.math.BigDecimal totalExternalFeeKrw,
                                       java.math.BigDecimal totalPrincipalKrw) {
        if (!configured(username)) return;
        java.math.BigDecimal todayNet = todayProfitKrw.subtract(todayExternalFeeKrw);
        java.math.BigDecimal totalNet = totalProfitKrw.subtract(totalExternalFeeKrw);
        java.math.BigDecimal returnPercent = totalPrincipalKrw.signum() <= 0
                ? java.math.BigDecimal.ZERO
                : totalNet.multiply(java.math.BigDecimal.valueOf(100))
                .divide(totalPrincipalKrw, 4, java.math.RoundingMode.HALF_UP);
        CompletableFuture.runAsync(() -> send(username, """
                [일일 차익거래 수익]

                오늘 거래 실현손익: %,.0f원
                오늘 외부 수수료: -%,.0f원
                오늘 최종 순수익: %,.0f원

                누적 거래 실현손익: %,.0f원
                누적 외부 수수료: -%,.0f원
                누적 최종 순수익: %,.0f원
                총 투입원금: %,.0f원
                누적 수익률: %,.4f%%

                집계시각: %s
                """.formatted(todayProfitKrw, todayExternalFeeKrw, todayNet,
                totalProfitKrw, totalExternalFeeKrw, totalNet, totalPrincipalKrw, returnPercent,
                TIME_FORMATTER.format(Instant.now()))));
    }

    public void sendNoTradeAlert(String username, long idleHours, long completedCycles7d,
                                 long doneOrders7d, java.math.BigDecimal netProfit7d,
                                 long upbitKrw, long bithumbKrw, long executableCandidates,
                                 String lastOrderText) {
        if (!configured(username)) return;
        CompletableFuture.runAsync(() -> send(username, """
                [자동거래 정체 감지]

                최근 %,d시간 동안 완료된 차익거래가 없습니다.

                최근 7일:
                완료 차익거래: %,d건
                체결 주문: %,d건
                수수료 차감 순수익: %,.0f원

                현재 KRW:
                UPBIT: %,d원
                BITHUMB: %,d원
                실행 가능 후보: %,d개

                마지막 체결 주문:
                %s

                확인할 것:
                원화 부족, 매도 코인 부족, API 권한, 체결 확인 대기, 기회 부족
                """.formatted(idleHours, completedCycles7d, doneOrders7d, netProfit7d,
                upbitKrw, bithumbKrw, executableCandidates, lastOrderText)));
    }

    public void sendSevenDayHealthReport(String username, long completedCycles7d, long failedCycles7d,
                                         long doneOrders7d, java.math.BigDecimal grossProfit7d,
                                         java.math.BigDecimal externalFees7d,
                                         java.math.BigDecimal netProfit7d,
                                         java.math.BigDecimal principalKrw) {
        if (!configured(username)) return;
        long totalCycles = completedCycles7d + failedCycles7d;
        java.math.BigDecimal returnPercent = principalKrw.signum() <= 0
                ? java.math.BigDecimal.ZERO
                : netProfit7d.multiply(java.math.BigDecimal.valueOf(100))
                .divide(principalKrw, 4, java.math.RoundingMode.HALF_UP);
        double failureRate = totalCycles <= 0 ? 0.0 : (double) failedCycles7d / totalCycles * 100.0;
        String verdict = netProfit7d.signum() > 0 && failureRate <= 5.0 && completedCycles7d >= 20
                ? "소폭 증액 검토 가능"
                : "현재 금액 유지 권장";
        CompletableFuture.runAsync(() -> send(username, """
                [최근 7일 자동거래 검증 리포트]

                완료 차익거래: %,d건
                실패 사이클: %,d건
                실패율: %.2f%%
                체결 주문: %,d건

                7일 거래 실현손익: %,.0f원
                7일 외부 수수료: -%,.0f원
                7일 최종 순수익: %,.0f원
                총 투입원금: %,.0f원
                7일 원금 대비 수익률: %,.4f%%

                판단:
                %s
                """.formatted(completedCycles7d, failedCycles7d, failureRate, doneOrders7d,
                grossProfit7d, externalFees7d, netProfit7d, principalKrw, returnPercent, verdict)));
    }

    public void notifyPrincipalProtection(String username, long principalKrw, long equityKrw,
                                          long shortageKrw, String message) {
        if (!configured(username)) return;
        CompletableFuture.runAsync(() -> send(username, """
                [원금 방어 모드]

                %s

                투입 원금: %,d원
                현재 평가액: %,d원
                원금 대비 부족액: %,d원

                이 모드는 코인 방향을 예측하지 않고, 평가액이 원금보다 낮을 때 추가 노출을 막기 위한 안전장치입니다.
                """.formatted(message, principalKrw, equityKrw, shortageKrw)));
    }

    public void notifyDelistingRisk(String username, String exchange, String symbol, long estimatedValueKrw) {
        if (!configured(username)) return;
        CompletableFuture.runAsync(() -> send(username, """
                [거래 정지 위험 코인 정리 필요]

                거래지원/출금 종료 위험 목록에 있는 코인을 보유 중입니다.

                거래소: %s
                코인: %s
                현재 추정 평가액: %,d원

                자동 차익거래와 신규 매수 대상에서는 즉시 제외했습니다.
                거래/출금이 완전히 막히기 전에 거래소 공지와 실제 보유 수량을 확인하고 정리하세요.

                이 코인을 정리하려면 텔레그램에 아래처럼 보내세요.
                %s 정리

                그러면 해당 코인만 매도 승인 요청을 다시 보내고, 승인 버튼을 눌렀을 때만 정리 주문을 넣습니다.
                """.formatted(exchange, symbol, estimatedValueKrw, symbol.replace("/KRW", ""))));
    }

    public void sendExternalFeeRecorded(String username, long amountKrw, java.math.BigDecimal todayTotalKrw) {
        if (!configured(username)) return;
        CompletableFuture.runAsync(() -> send(username, """
                [외부 수수료 기록]

                이번 수수료: %,d원
                오늘 누적 수수료: %,.0f원

                23:50 일일 순수익에서 차감합니다.
                """.formatted(amountKrw, todayTotalKrw)));
    }

    public void sendPrincipalDepositRecorded(String username, long amountKrw,
                                             java.math.BigDecimal todayTotalKrw,
                                             java.math.BigDecimal totalKrw) {
        if (!configured(username)) return;
        CompletableFuture.runAsync(() -> send(username, """
                [추가 원금 기록]

                이번 입금 원금: %,d원
                오늘 누적 입금 원금: %,.0f원
                현재 총 원금: %,.0f원

                앞으로 수익률은 현재 총 원금 기준으로 계산합니다.
                거래소 간 원화 이동은 입금으로 기록하지 말고 수수료만 기록하세요.
                """.formatted(amountKrw, todayTotalKrw, totalKrw)));
    }

    public void sendPrincipalDepositError(String chatId, String message) {
        notificationSettings.findByTelegramChatIdAndTelegramEnabledTrue(chatId)
                .ifPresent(value -> CompletableFuture.runAsync(() -> send(
                        value.getUser().getUsername(), "[원금 입력 오류]\n\n" + message)));
    }

    public void sendExternalFeeError(String chatId, String message) {
        notificationSettings.findByTelegramChatIdAndTelegramEnabledTrue(chatId)
                .ifPresent(value -> CompletableFuture.runAsync(() -> send(
                        value.getUser().getUsername(), "[수수료 입력 오류]\n\n" + message)));
    }

    public void sendTelegramCommandResult(String chatId, String title, String message) {
        notificationSettings.findByTelegramChatIdAndTelegramEnabledTrue(chatId)
                .ifPresent(value -> CompletableFuture.runAsync(() -> send(
                        value.getUser().getUsername(), "[" + title + "]\n\n" + message)));
    }

    public void notifyFeeChanged(String username,String exchange,Double oldBuy,Double oldSell,Double newBuy,Double newSell) {
        if (!configured(username)) return;
        String key = username + ":FEE:" + exchange + ":" + newBuy + ":" + newSell;
        Instant now = Instant.now();
        Instant previous = lastFeeAlertAt.get(key);
        if (previous != null && previous.plus(Duration.ofHours(6)).isAfter(now)) return;
        lastFeeAlertAt.put(key, now);
        CompletableFuture.runAsync(() -> send(username,"""
                [거래소 수수료 변경 감지]

                거래소: %s
                매수: %s%% → %s%%
                매도: %s%% → %s%%

                다음 차익 계산부터 변경된 실제 수수료를 적용합니다.
                """.formatted(exchange,feeText(oldBuy),feeText(newBuy),feeText(oldSell),feeText(newSell))));
    }

    private static String feeText(Double value){return value==null?"확인 불가":String.format(java.util.Locale.ROOT,"%.4f",value);}

    private boolean cooldownAllowed(ArbitrageOpportunity opportunity) {
        String key = opportunity.symbol() + "|" + opportunity.buyExchange() + "|" + opportunity.sellExchange();
        Instant now = Instant.now();
        Instant previous = lastAlertAt.get(key);
        if (previous != null && previous.plusSeconds(cooldownSeconds).isAfter(now)) return false;
        lastAlertAt.put(key, now);
        return true;
    }

    private boolean cooldownKeyAllowed(String key, long seconds) {
        Instant now = Instant.now();
        Instant previous = lastAlertAt.get(key);
        if (previous != null && previous.plusSeconds(seconds).isAfter(now)) return false;
        lastAlertAt.put(key, now);
        return true;
    }

    private void sendOpportunityDigest(String username, List<ArbitrageOpportunity> opportunities) {
        if (!settingsService.canSendTelegram(username, NotificationSettingsService.Channel.OPPORTUNITY)) return;
        if (!cooldownKeyAllowed(username + ":OPPORTUNITY_DIGEST", cooldownSeconds)) return;
        String rows = opportunities.stream()
                .limit(topN)
                .map(value -> "• %s · %s 매수 → %s 매도 · %.0f원 · %.3f%%"
                        .formatted(value.symbol(), value.buyExchange().toUpperCase(),
                                value.sellExchange().toUpperCase(), value.expectedProfitKrw(),
                                value.netProfitPercent()))
                .collect(java.util.stream.Collectors.joining("\n"));
        send(username, """
                [차익 기회 요약]

                기준을 통과한 상위 기회입니다.
                반복 알림을 줄이기 위해 개별 알림 대신 요약으로 보냅니다.

                %s

                다음 기회 요약은 최소 %,d분 뒤에 다시 보냅니다.
                실제 자동 주문 여부는 웹 화면의 자동거래 상태 진단에서 확인하세요.
                """.formatted(rows, Math.max(1, cooldownSeconds / 60)));
    }

    private void sendOpportunity(String username, ArbitrageOpportunity opportunity) {
        send(username, """
                [ARBITRAGE OPPORTUNITY]

                코인: %s
                경로: %s 매수 → %s 매도

                예상 매수가: %.8f
                예상 매도가: %.8f
                예상 수익률: %.2f%%
                예상 수익: %.0f원
                주문 기준금액: %.0f원

                감지시각: %s
                자동 주문 상태는 메인 화면에서 확인하세요.
                """.formatted(
                opportunity.symbol(),
                opportunity.buyExchange().toUpperCase(),
                opportunity.sellExchange().toUpperCase(),
                opportunity.buyPrice(),
                opportunity.sellPrice(),
                opportunity.netProfitPercent(),
                opportunity.expectedProfitKrw(),
                opportunity.investmentKrw(),
                TIME_FORMATTER.format(opportunity.detectedAt())
        ));
    }

    private void send(String username, String text) {
        send(username, text, null);
    }

    private void send(String username, String text, String replyMarkup) {
        try {
            String chatId = settingsService.telegramChatId(username);
            if (chatId.isBlank()) return;
            String body = "chat_id=" + encode(chatId)
                    + "&text=" + encode(text)
                    + "&disable_web_page_preview=true";
            if (replyMarkup != null && !replyMarkup.isBlank()) body += "&reply_markup=" + encode(replyMarkup);
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.error("Telegram notification failed | status={} body={}", response.statusCode(), response.body());
            } else {
                log.info("Telegram notification sent | username={}", username);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            log.error("Telegram notification failed", error);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
