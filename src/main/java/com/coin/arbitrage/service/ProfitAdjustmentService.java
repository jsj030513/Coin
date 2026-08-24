package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.LiveOrderEntity;
import com.coin.arbitrage.persistence.LiveOrderRepository;
import com.coin.arbitrage.persistence.ProfitAdjustmentEntity;
import com.coin.arbitrage.persistence.ProfitAdjustmentRepository;
import com.coin.arbitrage.persistence.UserAccountRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfitAdjustmentService {
    private final ProfitAdjustmentRepository adjustments;
    private final LiveOrderRepository orders;
    private final UserAccountRepository users;
    private final FeeProvider fees;

    public ProfitAdjustmentService(ProfitAdjustmentRepository adjustments, LiveOrderRepository orders,
                                   UserAccountRepository users, FeeProvider fees) {
        this.adjustments = adjustments;
        this.orders = orders;
        this.users = users;
        this.fees = fees;
    }

    @Transactional
    public BigDecimal recordAutoKrwRecovery(String username, String exchange, String symbol,
                                            BigDecimal quantity, BigDecimal estimatedProceedsKrw) {
        EstimatedAdjustment estimate = estimateAutoKrwRecovery(username, exchange, symbol,
                quantity, estimatedProceedsKrw);
        var user = users.findByUsername(username).orElseThrow();
        adjustments.save(new ProfitAdjustmentEntity(user, exchange, symbol, "AUTO_KRW_RECOVERY",
                estimate.realizedProfitKrw(), estimate.quantity(), estimate.proceedsKrw(), estimate.costBasisKrw(),
                "원화 자동 확보 매도 손익 · 추정 평균단가 기준"));
        return estimate.realizedProfitKrw();
    }

    public EstimatedAdjustment estimateAutoKrwRecovery(String username, String exchange, String symbol,
                                                       BigDecimal quantity, BigDecimal estimatedProceedsKrw) {
        BigDecimal safeQuantity = zeroIfNull(quantity);
        BigDecimal sellNet = zeroIfNull(estimatedProceedsKrw)
                .multiply(BigDecimal.ONE.subtract(rate(fees.sellFee(username, exchange))))
                .setScale(8, RoundingMode.HALF_UP);
        CostBasis costBasis = estimatedCostBasis(username, exchange, symbol, safeQuantity);
        BigDecimal realized = sellNet.subtract(costBasis.amountKrw()).setScale(8, RoundingMode.HALF_UP);
        return new EstimatedAdjustment(realized, safeQuantity, sellNet, costBasis.amountKrw(), costBasis.known());
    }

    private CostBasis estimatedCostBasis(String username, String exchange, String symbol, BigDecimal sellQuantity) {
        if (sellQuantity.signum() <= 0) return new CostBasis(BigDecimal.ZERO, false);
        BigDecimal heldQuantity = BigDecimal.ZERO;
        BigDecimal heldCost = BigDecimal.ZERO;
        for (LiveOrderEntity order : orders.findByUserUsernameAndExchangeAndSymbolOrderByCreatedAtAsc(
                username, exchange, symbol)) {
            if (!terminal(order.getStatus())) continue;
            BigDecimal quantity = zeroIfNull(order.getQuantity());
            if (quantity.signum() <= 0) continue;
            BigDecimal gross = zeroIfNull(order.getExecutedPrice()).multiply(quantity);
            if ("BUY".equalsIgnoreCase(order.getSide())) {
                heldQuantity = heldQuantity.add(quantity);
                heldCost = heldCost.add(gross.multiply(BigDecimal.ONE.add(rate(fees.buyFee(username, exchange)))));
            } else if ("SELL".equalsIgnoreCase(order.getSide()) && heldQuantity.signum() > 0) {
                BigDecimal remove = quantity.min(heldQuantity);
                BigDecimal averageCost = heldCost.divide(heldQuantity, 12, RoundingMode.HALF_UP);
                heldQuantity = heldQuantity.subtract(remove);
                heldCost = heldCost.subtract(averageCost.multiply(remove));
            }
        }
        if (heldQuantity.signum() <= 0) return new CostBasis(BigDecimal.ZERO, false);
        BigDecimal averageCost = heldCost.divide(heldQuantity, 12, RoundingMode.HALF_UP);
        return new CostBasis(averageCost.multiply(sellQuantity.min(heldQuantity)).setScale(8, RoundingMode.HALF_UP),
                heldQuantity.compareTo(sellQuantity) >= 0);
    }

    private static boolean terminal(String status) {
        return "done".equalsIgnoreCase(status) || "cancel".equalsIgnoreCase(status);
    }

    private static BigDecimal rate(double percent) {
        return BigDecimal.valueOf(percent).divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record EstimatedAdjustment(BigDecimal realizedProfitKrw, BigDecimal quantity,
                                      BigDecimal proceedsKrw, BigDecimal costBasisKrw,
                                      boolean costBasisKnown) { }
    private record CostBasis(BigDecimal amountKrw, boolean known) { }
}
