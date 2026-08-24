package com.coin.arbitrage.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FeeProviderTest {
    @Test
    void returnsExchangeSpecificBuyAndSellFees() {
        FeeProvider provider = new FeeProvider(0.05, 0.04, 0.02, 0.20);
        assertThat(provider.buyFee("UPBIT")).isEqualTo(0.05);
        assertThat(provider.sellFee("bithumb")).isEqualTo(0.04);
        assertThat(provider.buyFee("coinone")).isEqualTo(0.02);
        assertThat(provider.sellFee("korbit")).isEqualTo(0.20);
    }

    @Test
    void calculatesFeesAndRoiFromActualCashOutlay() {
        FeeProvider provider = new FeeProvider(0.05, 0.04, 0.02, 0.20);

        FeeProvider.CostBreakdown result = provider.calculate("bithumb", "upbit", 5_000, 5_050);

        assertThat(result.buyFeeKrw()).isEqualTo(2.0);
        assertThat(result.sellFeeKrw()).isEqualTo(2.525);
        assertThat(result.totalBuyCostKrw()).isEqualTo(5_002.0);
        assertThat(result.netSellProceedsKrw()).isEqualTo(5_047.475);
        assertThat(result.expectedProfitKrw()).isCloseTo(45.475,
                org.assertj.core.data.Offset.offset(1e-10));
        assertThat(result.netProfitPercent()).isCloseTo(0.9091363454,
                org.assertj.core.data.Offset.offset(1e-10));
    }
}
