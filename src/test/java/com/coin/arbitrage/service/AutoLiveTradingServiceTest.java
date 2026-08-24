package com.coin.arbitrage.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutoLiveTradingServiceTest {
    @Test
    void allowsFiveThousandClipButBlocksOversizedClipFromBalancedInventory() {
        var snapshot = snapshot("12", "12");
        var route = candidate("UPBIT", "BITHUMB");

        assertThat(AutoLiveTradingService.inventoryDirectionAllowed(
                snapshot, route, new BigDecimal("5"), 45)).isTrue();
        assertThat(AutoLiveTradingService.inventoryDirectionAllowed(
                snapshot, route, new BigDecimal("10"), 45)).isFalse();
    }

    @Test
    void blocksDirectionThatWorsensLargeInventoryImbalance() {
        var snapshot = snapshot("18", "6");
        var upbitBuy = candidate("UPBIT", "BITHUMB");

        assertThat(AutoLiveTradingService.inventoryDirectionAllowed(
                snapshot, upbitBuy, new BigDecimal("5"), 20)).isFalse();
    }

    @Test
    void allowsDirectionThatRepairsLargeInventoryImbalance() {
        var snapshot = snapshot("18", "6");
        var bithumbBuy = candidate("BITHUMB", "UPBIT");

        assertThat(AutoLiveTradingService.inventoryDirectionAllowed(
                snapshot, bithumbBuy, new BigDecimal("5"), 20)).isTrue();
    }

    private static LiveBalanceService.LiveBalanceResponse snapshot(String upbit, String bithumb) {
        return new LiveBalanceService.LiveBalanceResponse(Instant.now(), List.of(), List.of(
                balance("UPBIT", upbit), balance("BITHUMB", bithumb)), List.of(), null);
    }

    private static LiveBalanceService.LiveAssetBalance balance(String exchange, String quantity) {
        BigDecimal value = new BigDecimal(quantity);
        return new LiveBalanceService.LiveAssetBalance(
                exchange, "BTC", value, BigDecimal.ZERO, value, BigDecimal.ONE);
    }

    private static LiveBalanceService.LiveOpportunityReadiness candidate(String buy, String sell) {
        return new LiveBalanceService.LiveOpportunityReadiness("BTC/KRW", buy, sell,
                BigDecimal.valueOf(5_000), BigDecimal.valueOf(5), BigDecimal.valueOf(20_000),
                BigDecimal.valueOf(20), 50, 1, true, "ready", Instant.now());
    }
}
