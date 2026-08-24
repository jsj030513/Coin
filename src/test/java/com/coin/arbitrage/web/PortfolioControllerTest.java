package com.coin.arbitrage.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coin.arbitrage.persistence.OpportunityRepository;
import com.coin.arbitrage.service.LiveBalanceService;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortfolioControllerTest {
    @Test
    void portfolioUsesOnlyLiveExchangeBalances() {
        OpportunityRepository opportunities = mock(OpportunityRepository.class);
        LiveBalanceService liveBalances = mock(LiveBalanceService.class);
        when(opportunities.findTop1000ByOrderByDetectedAtDesc()).thenReturn(List.of());
        when(liveBalances.snapshot("owner")).thenReturn(new LiveBalanceService.LiveBalanceResponse(
                Instant.now(), List.of(), List.of(
                balance("UPBIT", "KRW", "50000", "0"),
                balance("BITHUMB", "KRW", "50000", "0"),
                balance("UPBIT", "XRP", "10", "1000")),
                List.of(), null));
        Principal principal = () -> "owner";

        PortfolioController.PortfolioResponse response =
                new PortfolioController(opportunities, liveBalances).portfolio(principal);

        assertThat(response.summary().currentKrwBalance()).isEqualTo(100_000);
        assertThat(response.summary().currentAssetValueKrw()).isEqualTo(10_000);
        assertThat(response.summary().currentPortfolioValueKrw()).isEqualTo(110_000);
    }

    private static LiveBalanceService.LiveAssetBalance balance(String exchange, String asset,
                                                               String amount, String avgPrice) {
        BigDecimal value = new BigDecimal(amount);
        return new LiveBalanceService.LiveAssetBalance(exchange, asset, value, BigDecimal.ZERO,
                value, new BigDecimal(avgPrice));
    }
}
