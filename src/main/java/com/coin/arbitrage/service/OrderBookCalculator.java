package com.coin.arbitrage.service;

import com.coin.arbitrage.domain.ExecutionEstimate;
import com.coin.arbitrage.domain.OrderBookSnapshot.Level;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class OrderBookCalculator {
    public ExecutionEstimate estimateBuy(List<Level> asks, double budgetKrw) {
        double remaining = budgetKrw;
        double base = 0;
        double spent = 0;
        for (Level level : asks) {
            if (level.price() <= 0 || level.quantity() <= 0) continue;
            double quantity = Math.min(level.quantity(), remaining / level.price());
            double cost = quantity * level.price();
            base += quantity;
            spent += cost;
            remaining -= cost;
            if (remaining <= Math.max(1e-8, budgetKrw * 1e-12)) break;
        }
        return new ExecutionEstimate(base == 0 ? 0 : spent / base, base, spent,
                remaining <= Math.max(1e-8, budgetKrw * 1e-12));
    }

    public ExecutionEstimate estimateSell(List<Level> bids, double baseAmount) {
        double remaining = baseAmount;
        double sold = 0;
        double received = 0;
        for (Level level : bids) {
            if (level.price() <= 0 || level.quantity() <= 0) continue;
            double quantity = Math.min(level.quantity(), remaining);
            sold += quantity;
            received += quantity * level.price();
            remaining -= quantity;
            if (remaining <= Math.max(1e-12, baseAmount * 1e-12)) break;
        }
        return new ExecutionEstimate(sold == 0 ? 0 : received / sold, sold, received,
                remaining <= Math.max(1e-12, baseAmount * 1e-12));
    }
}
