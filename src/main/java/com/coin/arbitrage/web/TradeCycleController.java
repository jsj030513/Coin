package com.coin.arbitrage.web;

import com.coin.arbitrage.persistence.TradeCycleEntity;
import com.coin.arbitrage.persistence.TradeCycleRepository;
import com.coin.arbitrage.persistence.ProfitAdjustmentRepository;
import com.coin.arbitrage.persistence.ExternalFeeRepository;
import com.coin.arbitrage.persistence.PrincipalDepositRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trade-cycles")
public class TradeCycleController {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final BigDecimal BANK_TRANSFER_FEE_KRW = BigDecimal.valueOf(1_000);
    private final TradeCycleRepository cycles;
    private final ProfitAdjustmentRepository adjustments;
    private final ExternalFeeRepository fees;
    private final PrincipalDepositRepository deposits;

    public TradeCycleController(TradeCycleRepository cycles, ProfitAdjustmentRepository adjustments,
                                ExternalFeeRepository fees, PrincipalDepositRepository deposits) {
        this.cycles = cycles;
        this.adjustments = adjustments;
        this.fees = fees;
        this.deposits = deposits;
    }

    @GetMapping
    public Response recent(Principal principal) {
        List<TradeCycleEntity> rows = cycles.findTop100ByUserUsernameOrderByCreatedAtDesc(principal.getName());
        LocalDate todayDate = LocalDate.now(SEOUL);
        Instant today = todayDate.atStartOfDay(SEOUL).toInstant();
        BigDecimal adjustmentTotal = adjustments.sumByUsername(principal.getName());
        BigDecimal adjustmentToday = adjustments.sumByUsernameSince(principal.getName(), today);
        BigDecimal grossTotal = cycles.sumRealizedProfit(principal.getName()).add(adjustmentTotal);
        BigDecimal grossToday = cycles.sumRealizedProfitSince(principal.getName(), today).add(adjustmentToday);
        BigDecimal feeTotal = fees.sumByUsername(principal.getName());
        BigDecimal feeToday = fees.sumByUsernameAndFeeDate(principal.getName(), todayDate);
        long feeCount = fees.countByUserUsername(principal.getName());
        long feeTodayCount = fees.countByUserUsernameAndFeeDate(principal.getName(), todayDate);
        BigDecimal principalTotal = deposits.sumByUsername(principal.getName());
        BigDecimal netTotal = grossTotal.subtract(feeTotal);
        BigDecimal netToday = grossToday.subtract(feeToday);
        BigDecimal returnPercent = principalTotal.signum() <= 0
                ? BigDecimal.ZERO
                : netTotal.multiply(BigDecimal.valueOf(100))
                .divide(principalTotal, 4, RoundingMode.HALF_UP);
        BigDecimal transferFeeEquivalentCount = feeTotal.divide(BANK_TRANSFER_FEE_KRW, 2, RoundingMode.HALF_UP);
        return new Response(grossTotal, grossToday, netTotal, netToday,
                feeTotal, feeToday, feeCount, feeTodayCount,
                BANK_TRANSFER_FEE_KRW, transferFeeEquivalentCount,
                principalTotal, returnPercent,
                rows.stream().map(Row::from).toList());
    }

    public record Response(BigDecimal realizedProfitKrw, BigDecimal todayProfitKrw,
                           BigDecimal netProfitKrw, BigDecimal todayNetProfitKrw,
                           BigDecimal externalFeeKrw, BigDecimal todayExternalFeeKrw,
                           long externalFeeCount, long todayExternalFeeCount,
                           BigDecimal bankTransferFeeKrw, BigDecimal bankTransferFeeEquivalentCount,
                           BigDecimal principalKrw, BigDecimal returnPercent,
                           List<Row> cycles) { }
    public record Row(String id, String symbol, String buyExchange, String sellExchange,
                      BigDecimal requestedKrw, BigDecimal expectedProfitKrw,
                      BigDecimal realizedProfitKrw, String status, String detail,
                      Instant createdAt, Instant updatedAt) {
        static Row from(TradeCycleEntity value) {
            return new Row(value.getId(), value.getSymbol(), value.getBuyExchange(), value.getSellExchange(),
                    value.getRequestedKrw(), value.getExpectedProfitKrw(), value.getRealizedProfitKrw(),
                    value.getStatus().name(), value.getDetail(), value.getCreatedAt(), value.getUpdatedAt());
        }
    }
}
