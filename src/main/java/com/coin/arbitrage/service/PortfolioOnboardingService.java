package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.PortfolioOnboardingEntity;
import com.coin.arbitrage.persistence.PortfolioOnboardingRepository;
import com.coin.arbitrage.persistence.PrincipalDepositRepository;
import com.coin.arbitrage.persistence.UserAccountRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortfolioOnboardingService {
    private final PortfolioOnboardingRepository rows;
    private final LiveBalanceService balances;
    private final ArbitrageEngine engine;
    private final TelegramNotificationService telegram;
    private final NotificationSettingsService notificationSettings;
    private final PortfolioPlanService plans;
    private final TelegramTradeApprovalService approvals;
    private final UserTradingPreferenceService preferences;
    private final PrincipalDepositRepository deposits;
    private final UserAccountRepository users;

    public PortfolioOnboardingService(PortfolioOnboardingRepository rows, LiveBalanceService balances,
                                      ArbitrageEngine engine, TelegramNotificationService telegram,
                                      NotificationSettingsService notificationSettings,
                                      PortfolioPlanService plans, TelegramTradeApprovalService approvals,
                                      UserTradingPreferenceService preferences, PrincipalDepositRepository deposits,
                                      UserAccountRepository users) {
        this.rows=rows; this.balances=balances; this.engine=engine; this.telegram=telegram;
        this.notificationSettings=notificationSettings; this.plans=plans; this.approvals=approvals;
        this.preferences=preferences; this.deposits=deposits; this.users=users;
    }

    @Scheduled(fixedDelayString="${portfolio.onboarding-check-interval-ms:60000}", initialDelayString="${portfolio.onboarding-initial-delay-ms:30000}")
    @Transactional
    public void inspectPendingUsers() {
        rows.findByStatus(PortfolioOnboardingEntity.Status.PENDING).forEach(row -> {
            String username=row.getUser().getUsername();
            if (!telegram.configured(username)) return;
            try { inspect(row.getId(), username); } catch (RuntimeException ignored) { }
        });
    }

    @Transactional
    public void inspect(Long id, String username) {
        PortfolioOnboardingEntity row=rows.findById(id).orElseThrow();
        if (row.getStatus()!=PortfolioOnboardingEntity.Status.PENDING) return;
        LiveBalanceService.LiveBalanceResponse snapshot=balances.snapshot(username);
        long connected=snapshot.statuses().stream()
                .filter(s -> ("UPBIT".equals(s.exchange())||"BITHUMB".equals(s.exchange())) && s.connected()).count();
        if (connected<2) return;
        List<Issue> issues=issues(snapshot);
        String token=UUID.randomUUID().toString().replace("-", "");
        row.waiting(token); rows.save(row);
        if (issues.isEmpty()) {
            sendRecommendations(row, username, token);
            row.complete(); rows.save(row);
            return;
        }
        telegram.sendPortfolioCompatibility(username, token, issues.stream()
                .map(i -> "• %s %s · 약 %,d원 · %s".formatted(i.exchange(),i.symbol(),i.estimatedKrw(),i.reason()))
                .collect(java.util.stream.Collectors.joining("\n")));
    }

    @Transactional
    public InitialSetupReport startInitialSetup(String username) {
        LiveBalanceService.LiveBalanceResponse snapshot=balances.snapshot(username);
        ensureConnected(snapshot);
        AppliedSettings applied=applyRecommendedSettings(username, snapshot);
        List<KeptHolding> kept=keptHoldings(snapshot);
        List<Issue> issues=issues(snapshot);
        PortfolioOnboardingEntity row=rows.findByUserUsername(username).orElseGet(() ->
                rows.save(new PortfolioOnboardingEntity(users.findByUsername(username).orElseThrow())));
        row.resetPending();
        rows.save(row);
        inspect(row.getId(), username);
        PortfolioPlanService.PortfolioPlan plan=plans.plan(username);
        List<Recommendation> recommendations=plan.symbols().stream().limit(5)
                .map(value -> new Recommendation(value.symbol(), value.averageProfitPercent(),
                        value.upbitSuggestedBuyKrw(), value.bithumbSuggestedBuyKrw(),
                        value.upbit().estimatedValueKrw(), value.bithumb().estimatedValueKrw()))
                .toList();
        InitialSetupReport report=new InitialSetupReport(applied, kept, issues, recommendations,
                plan.upbitCashKrw(), plan.bithumbCashKrw(),
                "초기 세팅 완료: 양쪽 보유 공통 코인은 유지하고, 정리/추천 매수는 텔레그램 승인 흐름으로 남겼습니다.");
        telegram.sendInitialSetupReport(username, formatReport(report));
        return report;
    }

    @Transactional
    public String handleCallback(String token, String action, String chatId) {
        PortfolioOnboardingEntity row=rows.findByDecisionToken(token).orElse(null);
        if (row==null) return "요청이 없거나 만료되었습니다.";
        String username=row.getUser().getUsername();
        if (!notificationSettings.telegramChatId(username).equals(chatId)) return "이 계정의 요청이 아닙니다.";
        if ("buy".equals(action)) {
            row.consumeToken(); rows.save(row);
            approvals.requestRecommendedSeed(username);
            return "추천 매수 대상을 다시 계산해 승인 요청을 보냈습니다.";
        }
        if (row.getStatus()!=PortfolioOnboardingEntity.Status.WAITING_DECISION) return "이미 처리된 선택입니다.";
        if ("keep".equals(action)) {
            row.decide(PortfolioOnboardingEntity.Status.KEEP_SELECTED);
            sendRecommendations(row,username,token); row.complete(); rows.save(row);
            return "기존 코인을 유지하고 추천 단계로 진행합니다.";
        }
        if ("sell".equals(action)) {
            row.decide(PortfolioOnboardingEntity.Status.SELL_SELECTED); rows.save(row);
            Set<String> targets=issues(balances.snapshot(username)).stream()
                    .map(i -> i.exchange()+"|"+i.symbol()).collect(java.util.stream.Collectors.toSet());
            if (targets.isEmpty()) { sendRecommendations(row,username,token); row.complete(); return "정리할 코인이 없어 추천 단계로 진행합니다."; }
            try { approvals.requestLiquidation(username,targets); }
            catch (RuntimeException error) {
                sendRecommendations(row,username,token); row.complete(); rows.save(row);
                return "직접 매도 가능한 대상이 없습니다. 추천 목록을 보냈습니다.";
            }
            sendRecommendations(row,username,token); row.complete(); rows.save(row);
            return "정리 대상의 최종 매도 승인 요청을 보냈습니다.";
        }
        return "지원하지 않는 선택입니다.";
    }

    private void sendRecommendations(PortfolioOnboardingEntity row,String username,String token) {
        PortfolioPlanService.PortfolioPlan plan=plans.plan(username);
        String detail=plan.symbols().stream().limit(5).map(s ->
                "• %s · 평균 %.2f%% · 양쪽 각 %,d원 권장".formatted(s.symbol(),s.averageProfitPercent(),
                        Math.max(s.upbitSuggestedBuyKrw(),s.bithumbSuggestedBuyKrw())))
                .collect(java.util.stream.Collectors.joining("\n"));
        if (detail.isBlank()) detail="아직 추천할 만큼의 탐지 데이터가 없습니다. 시장 데이터를 더 수집한 뒤 다시 확인해 주세요.";
        telegram.sendPortfolioRecommendations(username,token,detail);
    }

    private List<Issue> issues(LiveBalanceService.LiveBalanceResponse snapshot) {
        Set<String> common=engine.commonSymbols();
        Map<String,Set<String>> exchanges=new HashMap<>();
        snapshot.balances().stream().filter(v -> !"KRW".equals(v.asset()) && v.total().signum()>0)
                .forEach(v -> exchanges.computeIfAbsent(v.asset(),k->new HashSet<>()).add(v.exchange()));
        List<Issue> result=new ArrayList<>();
        snapshot.balances().stream().filter(v -> !"KRW".equals(v.asset()) && v.total().signum()>0).forEach(v -> {
            String symbol=v.asset()+"/KRW";
            boolean oneSided=!(exchanges.getOrDefault(v.asset(),Set.of()).contains("UPBIT")
                    && exchanges.getOrDefault(v.asset(),Set.of()).contains("BITHUMB"));
            if (!oneSided && common.contains(symbol)) return;
            BigDecimal bid=engine.currentBid(symbol,v.exchange());
            long estimated=bid.signum()>0?v.total().multiply(bid).setScale(0,RoundingMode.DOWN).longValue():0;
            BigDecimal cost=v.avgBuyPrice().signum()>0?v.total().multiply(v.avgBuyPrice()):BigDecimal.ZERO;
            Long estimatedPnl=v.avgBuyPrice().signum()>0
                    ? estimated-cost.setScale(0,RoundingMode.DOWN).longValue()
                    : null;
            result.add(new Issue(v.exchange(),symbol,estimated,estimatedPnl,
                    common.contains(symbol)?"한 거래소에만 보유":"양 거래소 공통마켓 아님"));
        });
        return result;
    }

    private List<KeptHolding> keptHoldings(LiveBalanceService.LiveBalanceResponse snapshot) {
        Set<String> common=engine.commonSymbols();
        Map<String,List<LiveBalanceService.LiveAssetBalance>> byAsset=new TreeMap<>();
        snapshot.balances().stream().filter(v -> !"KRW".equals(v.asset()) && v.total().signum()>0)
                .forEach(value -> byAsset.computeIfAbsent(value.asset(), ignored -> new ArrayList<>()).add(value));
        List<KeptHolding> result=new ArrayList<>();
        byAsset.forEach((asset, values) -> {
            boolean upbit=values.stream().anyMatch(v -> "UPBIT".equals(v.exchange()));
            boolean bithumb=values.stream().anyMatch(v -> "BITHUMB".equals(v.exchange()));
            String symbol=asset + "/KRW";
            if (!upbit || !bithumb || !common.contains(symbol)) return;
            long upbitValue=estimatedValue(values, "UPBIT", symbol);
            long bithumbValue=estimatedValue(values, "BITHUMB", symbol);
            result.add(new KeptHolding(symbol, upbitValue, bithumbValue, upbitValue+bithumbValue,
                    "양 거래소에 이미 있고 공통 KRW 마켓이라 유지"));
        });
        return result;
    }

    private long estimatedValue(List<LiveBalanceService.LiveAssetBalance> values, String exchange, String symbol) {
        BigDecimal bid=engine.currentBid(symbol, exchange);
        if (bid.signum()<=0) return 0;
        return values.stream().filter(v -> exchange.equals(v.exchange())).findFirst()
                .map(v -> v.total().multiply(bid).setScale(0,RoundingMode.DOWN).longValue())
                .orElse(0L);
    }

    private AppliedSettings applyRecommendedSettings(String username, LiveBalanceService.LiveBalanceResponse snapshot) {
        BigDecimal principal=deposits.sumByUsername(username);
        long estimatedTotal=principal.signum()>0?principal.setScale(0,RoundingMode.DOWN).longValue():estimatedPortfolioValue(snapshot);
        if (estimatedTotal < 50_000) estimatedTotal=50_000;
        int symbols=UserTradingPreferenceService.recommendedSymbolCount(estimatedTotal);
        long reserve=roundToThousand(Math.max(5_000, Math.min(30_000, Math.round(estimatedTotal * 0.08))));
        long usable=Math.max(10_000, estimatedTotal - reserve * 2);
        long target=roundToThousand(Math.max(5_000, Math.min(12_000, usable / Math.max(2, symbols * 2L))));
        long maxSeed=target;
        preferences.updateAutoSymbols(username, estimatedTotal, 0);
        plans.updateSettings(username, new PortfolioPlanService.SettingsRequest(symbols, target, reserve, maxSeed, 600));
        return new AppliedSettings(estimatedTotal, symbols, target, reserve, maxSeed, 600);
    }

    private long estimatedPortfolioValue(LiveBalanceService.LiveBalanceResponse snapshot) {
        long total=0;
        for (LiveBalanceService.LiveAssetBalance value : snapshot.balances()) {
            if ("KRW".equals(value.asset())) {
                total+=value.total().setScale(0,RoundingMode.DOWN).longValue();
                continue;
            }
            String symbol=value.asset()+"/KRW";
            BigDecimal bid=engine.currentBid(symbol,value.exchange());
            if (bid.signum()>0) total+=value.total().multiply(bid).setScale(0,RoundingMode.DOWN).longValue();
        }
        return total;
    }

    private static long roundToThousand(long value) {
        return Math.max(0, value / 1000 * 1000);
    }

    private static void ensureConnected(LiveBalanceService.LiveBalanceResponse snapshot) {
        long connected=snapshot.statuses().stream()
                .filter(s -> ("UPBIT".equals(s.exchange())||"BITHUMB".equals(s.exchange())) && s.connected()).count();
        if (connected<2) throw new IllegalStateException("업비트와 빗썸 잔고 조회가 모두 정상이어야 초기 세팅을 실행할 수 있습니다.");
    }

    private String formatReport(InitialSetupReport report) {
        String kept=report.keptHoldings().isEmpty() ? "• 유지할 양쪽 보유 코인 없음"
                : report.keptHoldings().stream().limit(8)
                .map(v -> "• %s · 합산 %,d원 (UPBIT %,d원 / BITHUMB %,d원)".formatted(
                        v.symbol(), v.totalEstimatedKrw(), v.upbitEstimatedKrw(), v.bithumbEstimatedKrw()))
                .collect(java.util.stream.Collectors.joining("\n"));
        String issues=report.issues().isEmpty() ? "• 정리 후보 없음"
                : report.issues().stream().limit(8)
                .map(v -> "• %s %s · 예상 회수 %,d원 · 추정손익 %s · %s".formatted(
                        v.exchange(), v.symbol(), v.estimatedKrw(),
                        v.estimatedPnlKrw()==null?"계산 불가":"%,d원".formatted(v.estimatedPnlKrw()),
                        v.reason()))
                .collect(java.util.stream.Collectors.joining("\n"));
        String recs=report.recommendations().isEmpty() ? "• 추천 데이터 부족"
                : report.recommendations().stream().limit(5)
                .map(v -> "• %s · 평균 %.2f%% · 필요 UPBIT %,d원 / BITHUMB %,d원".formatted(
                        v.symbol(), v.averageProfitPercent(), v.upbitSuggestedBuyKrw(), v.bithumbSuggestedBuyKrw()))
                .collect(java.util.stream.Collectors.joining("\n"));
        AppliedSettings s=report.settings();
        long issueRecovery=report.issues().stream().mapToLong(Issue::estimatedKrw).sum();
        long issuePnl=report.issues().stream().filter(v -> v.estimatedPnlKrw()!=null).mapToLong(Issue::estimatedPnlKrw).sum();
        return """
                설정:
                • 기준 원금/평가액: %,d원
                • 추천 코인 수: %d개
                • 코인당 목표: 거래소별 %,d원
                • 거래소별 현금 보유: %,d원
                • 1회 초기매수 상한: %,d원

                유지:
                %s

                정리 후보:
                %s

                정리 후보 합계:
                • 예상 회수액: %,d원
                • 평균매수가 기준 추정손익: %,d원

                추천 매수 후보:
                %s

                현재 KRW:
                • UPBIT %,d원 / BITHUMB %,d원

                실제 매도·매수는 텔레그램 승인 전까지 실행되지 않습니다.
                """.formatted(s.capitalBasisKrw(), s.targetSymbolCount(), s.targetKrwPerSymbolPerExchange(),
                s.cashReserveKrwPerExchange(), s.maxSeedBuyKrw(),
                kept, issues, issueRecovery, issuePnl, recs, report.upbitCashKrw(), report.bithumbCashKrw());
    }

    public record AppliedSettings(long capitalBasisKrw, int targetSymbolCount,
                                  long targetKrwPerSymbolPerExchange, long cashReserveKrwPerExchange,
                                  long maxSeedBuyKrw, long seedBuyCooldownSeconds) { }
    public record KeptHolding(String symbol, long upbitEstimatedKrw, long bithumbEstimatedKrw,
                              long totalEstimatedKrw, String reason) { }
    public record Issue(String exchange,String symbol,long estimatedKrw,Long estimatedPnlKrw,String reason) { }
    public record Recommendation(String symbol, double averageProfitPercent,
                                 long upbitSuggestedBuyKrw, long bithumbSuggestedBuyKrw,
                                 long upbitCurrentKrw, long bithumbCurrentKrw) { }
    public record InitialSetupReport(AppliedSettings settings, List<KeptHolding> keptHoldings,
                                     List<Issue> issues, List<Recommendation> recommendations,
                                     long upbitCashKrw, long bithumbCashKrw, String message) { }
}
