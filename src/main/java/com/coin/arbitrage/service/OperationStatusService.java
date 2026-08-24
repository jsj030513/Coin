package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.ExternalFeeRepository;
import com.coin.arbitrage.persistence.LiveOrderEntity;
import com.coin.arbitrage.persistence.LiveOrderRepository;
import com.coin.arbitrage.persistence.PrincipalDepositRepository;
import com.coin.arbitrage.persistence.ProfitAdjustmentRepository;
import com.coin.arbitrage.persistence.TradeCycleEntity;
import com.coin.arbitrage.persistence.TradeCycleRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OperationStatusService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final List<TradeCycleEntity.Status> OPEN_CYCLE_STATUSES =
            List.of(TradeCycleEntity.Status.PENDING, TradeCycleEntity.Status.SUBMITTED);

    private final SystemStatusService systemStatus;
    private final LiveBalanceService balances;
    private final PrincipalProtectionService principalProtection;
    private final TradeCycleRepository cycles;
    private final ProfitAdjustmentRepository adjustments;
    private final ExternalFeeRepository fees;
    private final PrincipalDepositRepository deposits;
    private final LiveOrderRepository orders;

    public OperationStatusService(SystemStatusService systemStatus,
                                  LiveBalanceService balances,
                                  PrincipalProtectionService principalProtection,
                                  TradeCycleRepository cycles,
                                  ProfitAdjustmentRepository adjustments,
                                  ExternalFeeRepository fees,
                                  PrincipalDepositRepository deposits,
                                  LiveOrderRepository orders) {
        this.systemStatus = systemStatus;
        this.balances = balances;
        this.principalProtection = principalProtection;
        this.cycles = cycles;
        this.adjustments = adjustments;
        this.fees = fees;
        this.deposits = deposits;
        this.orders = orders;
    }

    public Status status(String username) {
        SystemStatusService.Status system = systemStatus.status(username);
        BigDecimal principal = deposits.sumByUsername(username);
        BigDecimal totalGross = cycles.sumRealizedProfit(username).add(adjustments.sumByUsername(username));
        BigDecimal totalFees = fees.sumByUsername(username);
        BigDecimal totalNet = totalGross.subtract(totalFees);
        Instant sevenDaysAgo = Instant.now().minus(Duration.ofDays(7));
        LocalDate sinceDate = LocalDate.now(SEOUL).minusDays(6);
        BigDecimal sevenGross = cycles.sumRealizedProfitSince(username, sevenDaysAgo)
                .add(adjustments.sumByUsernameSince(username, sevenDaysAgo));
        BigDecimal sevenFees = BigDecimal.ZERO;
        for (int i = 0; i < 7; i++) {
            sevenFees = sevenFees.add(fees.sumByUsernameAndFeeDate(username, sinceDate.plusDays(i)));
        }
        BigDecimal sevenNet = sevenGross.subtract(sevenFees);
        long completed7d = cycles.countByUserUsernameAndStatusAndCreatedAtAfter(
                username, TradeCycleEntity.Status.COMPLETED, sevenDaysAgo);
        long failed7d = cycles.countByUserUsernameAndStatusInAndCreatedAtAfter(username,
                List.of(TradeCycleEntity.Status.FAILED, TradeCycleEntity.Status.TIMED_OUT,
                        TradeCycleEntity.Status.MISMATCH), sevenDaysAgo);
        long total7d = completed7d + failed7d;
        double failureRate7d = total7d <= 0 ? 0.0 : (double) failed7d / total7d * 100.0;
        long doneOrders7d = orders.countByUserUsernameAndStatusAndCreatedAtAfter(username, "done", sevenDaysAgo);
        long openCycles = cycles.countByUserUsernameAndStatusIn(username, OPEN_CYCLE_STATUSES);
        TradeCycleEntity oldestOpen = cycles.findTopByUserUsernameAndStatusInOrderByCreatedAtAsc(
                username, OPEN_CYCLE_STATUSES);
        long oldestOpenMinutes = oldestOpen == null ? 0
                : Math.max(0, Duration.between(oldestOpen.getUpdatedAt(), Instant.now()).toMinutes());
        boolean cycleStuck = oldestOpenMinutes >= 30;
        TradeCycleEntity latestCycle = cycles.findTopByUserUsernameOrderByCreatedAtDesc(username);
        LiveOrderEntity latestOrder = orders.findTop100ByUserUsernameOrderByCreatedAtDesc(username)
                .stream().findFirst().orElse(null);

        try {
            LiveBalanceService.LiveBalanceResponse snapshot = balances.snapshot(username);
            PrincipalProtectionService.Decision protection = principalProtection.decide(username, snapshot);
            Readiness readiness = readiness(snapshot);
            BigDecimal equity = protection.enabled() ? protection.equityKrw() : equity(snapshot);
            BigDecimal equityGap = equity.subtract(principal);
            String code = code(system, protection, readiness, cycleStuck);
            String label = label(code);
            String message = message(code, readiness, protection, system, oldestOpenMinutes);
            SalesVerdict verdict = verdict(principal, equityGap, sevenNet, completed7d,
                    failureRate7d, cycleStuck, protection.protecting());
            return new Status(code, label, severity(code), message,
                    system.trading().masterEnabled(), system.trading().userEnabled(), system.trading().active(),
                    system.scanHealthy(), system.scanStale(),
                    protection.enabled(), protection.protecting(),
                    principal, equity, equityGap, protection.shortageKrw(),
                    readiness.executableCandidates(), readiness.krwShortageCandidates(),
                    readiness.coinShortageCandidates(), readiness.bestCandidate(),
                    openCycles, cycleStuck, oldestOpenMinutes,
                    latestCycle == null ? null : latestCycle.getUpdatedAt(),
                    latestOrder == null ? null : latestOrder.getCreatedAt(),
                    snapshot.rebalance(), new SevenDayReport(completed7d, failed7d, doneOrders7d,
                    sevenGross, sevenFees, sevenNet, failureRate7d), totalGross, totalFees, totalNet, verdict);
        } catch (RuntimeException error) {
            SalesVerdict verdict = new SalesVerdict("HOLD", "API 또는 잔고 조회 오류가 있어 판매/증액 판단을 보류합니다.");
            return new Status("API_ERROR", "API 확인 필요", "danger",
                    "거래소 잔고 또는 기회 데이터를 조회하지 못했습니다: " + safe(error.getMessage()),
                    system.trading().masterEnabled(), system.trading().userEnabled(), system.trading().active(),
                    system.scanHealthy(), system.scanStale(),
                    false, false, principal, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    0, 0, 0, null, openCycles, cycleStuck, oldestOpenMinutes,
                    latestCycle == null ? null : latestCycle.getUpdatedAt(),
                    latestOrder == null ? null : latestOrder.getCreatedAt(),
                    null, new SevenDayReport(completed7d, failed7d, doneOrders7d,
                    sevenGross, sevenFees, sevenNet, failureRate7d), totalGross, totalFees, totalNet, verdict);
        }
    }

    private static String code(SystemStatusService.Status system, PrincipalProtectionService.Decision protection,
                               Readiness readiness, boolean cycleStuck) {
        if (!system.trading().masterEnabled()) return "MASTER_LOCKED";
        if (!system.trading().userEnabled()) return "USER_STOPPED";
        if (!system.scanHealthy()) return "SCANNER_DEGRADED";
        if (system.verifiedConnections() < system.totalConnections()) return "API_DISCONNECTED";
        if (cycleStuck) return "ORDER_STUCK";
        if (protection.protecting()) return "PRINCIPAL_PROTECTION";
        if (readiness.executableCandidates() > 0) return "READY";
        if (readiness.krwShortageCandidates() > 0) return "KRW_SHORTAGE";
        if (readiness.coinShortageCandidates() > 0) return "COIN_SHORTAGE";
        return "WAITING_MARKET";
    }

    private static String label(String code) {
        return switch (code) {
            case "READY" -> "운용 가능";
            case "WAITING_MARKET" -> "시장 대기";
            case "PRINCIPAL_PROTECTION" -> "원금 방어 중";
            case "KRW_SHORTAGE" -> "원화 부족";
            case "COIN_SHORTAGE" -> "매도 재고 부족";
            case "ORDER_STUCK" -> "체결 확인 지연";
            case "API_DISCONNECTED", "API_ERROR" -> "API 확인 필요";
            case "SCANNER_DEGRADED" -> "스캐너 점검 필요";
            case "MASTER_LOCKED" -> "서버 자동매매 잠금";
            case "USER_STOPPED" -> "사용자 정지";
            default -> "관찰 필요";
        };
    }

    private static String severity(String code) {
        return switch (code) {
            case "READY", "WAITING_MARKET" -> "ok";
            case "PRINCIPAL_PROTECTION", "KRW_SHORTAGE", "COIN_SHORTAGE" -> "warn";
            default -> "danger";
        };
    }

    private static String message(String code, Readiness readiness, PrincipalProtectionService.Decision protection,
                                  SystemStatusService.Status system, long oldestOpenMinutes) {
        return switch (code) {
            case "READY" -> "실행 가능한 후보가 있습니다. 순수익 기준을 통과하면 자동 주문을 시도합니다.";
            case "WAITING_MARKET" -> "현재는 수수료와 안전 조건을 이긴 기회가 없어 대기 중입니다.";
            case "PRINCIPAL_PROTECTION" -> "평가액이 원금보다 낮아 신규 매수와 재고 보충을 막고 있습니다. 기존 보유분 기반 기회만 봅니다.";
            case "KRW_SHORTAGE" -> "기회는 있지만 매수 거래소 원화가 부족합니다. 자동 원화 확보가 불리하면 텔레그램으로 이동 금액을 안내합니다.";
            case "COIN_SHORTAGE" -> "기회는 있지만 매도 거래소 코인 재고가 부족합니다. 원금 방어 상태가 아니면 자동 재고 확보를 검토합니다.";
            case "ORDER_STUCK" -> "체결 확인 중인 사이클이 %,d분 이상 오래됐습니다. 주문 내역 확인 또는 복구가 필요합니다.".formatted(oldestOpenMinutes);
            case "API_DISCONNECTED" -> "업비트/빗썸 API 연결 중 일부가 검증되지 않았습니다.";
            case "SCANNER_DEGRADED" -> system.scan().lastError() == null ? "시장 스캐너가 오래 갱신되지 않았습니다."
                    : "시장 스캐너 오류: " + safe(system.scan().lastError());
            case "MASTER_LOCKED" -> "서버 마스터 자동매매 스위치가 꺼져 있습니다.";
            case "USER_STOPPED" -> "사용자 자동매매 스위치가 꺼져 있습니다.";
            default -> readiness.bestCandidate() == null ? "상태를 계산하는 중입니다." : "최고 후보를 관찰 중입니다.";
        };
    }

    private SalesVerdict verdict(BigDecimal principal, BigDecimal equityGap, BigDecimal sevenNet,
                                 long completed7d, double failureRate7d, boolean cycleStuck,
                                 boolean protecting) {
        if (principal.signum() <= 0) {
            return new SalesVerdict("HOLD", "투입 원금 기록이 없어 상품성 판단을 보류합니다.");
        }
        if (cycleStuck) {
            return new SalesVerdict("HOLD", "체결 확인 지연이 있어 판매 전 교착 복구 안정성이 더 필요합니다.");
        }
        if (protecting || equityGap.signum() < 0) {
            return new SalesVerdict("OBSERVE", "평가액이 원금보다 낮아 증액/판매보다 원금 회복 데이터가 먼저 필요합니다.");
        }
        if (sevenNet.signum() > 0 && completed7d >= 20 && failureRate7d <= 5.0) {
            return new SalesVerdict("PASS", "7일 기준 순수익·거래수·실패율이 최소 상품 검증선을 통과했습니다.");
        }
        return new SalesVerdict("OBSERVE", "아직 7일 거래수 또는 순수익 안정성이 부족합니다. 현재 금액으로 관찰이 맞습니다.");
    }

    private Readiness readiness(LiveBalanceService.LiveBalanceResponse snapshot) {
        List<LiveBalanceService.LiveOpportunityReadiness> rows = snapshot.readiness();
        long executable = rows.stream().filter(LiveBalanceService.LiveOpportunityReadiness::executable).count();
        long krwShortage = rows.stream()
                .filter(value -> value.reason() != null && value.reason().contains("KRW 부족"))
                .count();
        long coinShortage = rows.stream()
                .filter(value -> value.reason() != null && value.reason().contains("코인 부족"))
                .count();
        Candidate best = rows.stream()
                .max(Comparator.comparingDouble(LiveBalanceService.LiveOpportunityReadiness::expectedProfitKrw))
                .map(value -> new Candidate(value.symbol(), value.buyExchange(), value.sellExchange(),
                        value.expectedProfitKrw(), value.netProfitPercent(), value.reason(), value.executable()))
                .orElse(null);
        return new Readiness(executable, krwShortage, coinShortage, best);
    }

    private static BigDecimal equity(LiveBalanceService.LiveBalanceResponse snapshot) {
        BigDecimal krw = snapshot.balances().stream()
                .filter(value -> "KRW".equals(value.asset()))
                .map(LiveBalanceService.LiveAssetBalance::free)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal assets = snapshot.balances().stream()
                .filter(value -> !"KRW".equals(value.asset()))
                .map(value -> value.total().multiply(value.avgBuyPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return krw.add(assets).setScale(0, RoundingMode.DOWN);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "상세 오류 없음";
        return value.length() > 160 ? value.substring(0, 160) + "…" : value;
    }

    private record Readiness(long executableCandidates, long krwShortageCandidates,
                             long coinShortageCandidates, Candidate bestCandidate) { }

    public record Status(String code, String label, String severity, String message,
                         boolean masterEnabled, boolean userEnabled, boolean active,
                         boolean scanHealthy, boolean scanStale,
                         boolean principalProtectionEnabled, boolean principalProtecting,
                         BigDecimal principalKrw, BigDecimal equityKrw, BigDecimal equityGapKrw,
                         BigDecimal principalShortageKrw,
                         long executableCandidates, long krwShortageCandidates,
                         long coinShortageCandidates, Candidate bestCandidate,
                         long openCycles, boolean cycleStuck, long oldestOpenCycleMinutes,
                         Instant latestCycleAt, Instant latestOrderAt,
                         LiveBalanceService.KrwRebalanceRecommendation krwRebalance,
                         SevenDayReport sevenDay, BigDecimal totalGrossProfitKrw,
                         BigDecimal totalExternalFeeKrw, BigDecimal totalNetProfitKrw,
                         SalesVerdict salesVerdict) { }

    public record Candidate(String symbol, String buyExchange, String sellExchange,
                            double expectedProfitKrw, double netProfitPercent,
                            String reason, boolean executable) { }

    public record SevenDayReport(long completedCycles, long failedCycles, long doneOrders,
                                 BigDecimal grossProfitKrw, BigDecimal externalFeeKrw,
                                 BigDecimal netProfitKrw, double failureRatePercent) { }

    public record SalesVerdict(String code, String message) { }
}
