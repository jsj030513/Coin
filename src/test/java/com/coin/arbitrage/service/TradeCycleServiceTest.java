package com.coin.arbitrage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coin.arbitrage.persistence.LiveOrderEntity;
import com.coin.arbitrage.persistence.LiveOrderRepository;
import com.coin.arbitrage.persistence.TradeCycleEntity;
import com.coin.arbitrage.persistence.TradeCycleRepository;
import com.coin.arbitrage.persistence.UserAccountEntity;
import com.coin.arbitrage.persistence.UserAccountRepository;
import com.coin.arbitrage.config.LiveTradingProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TradeCycleServiceTest {
    private TradeCycleRepository cycles;
    private LiveOrderRepository orders;
    private TradingSettingsService trading;
    private TelegramNotificationService telegram;
    private TradeCycleService service;
    private UserAccountEntity user;

    @BeforeEach
    void setUp() {
        cycles = mock(TradeCycleRepository.class);
        orders = mock(LiveOrderRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        RiskSettingsService risk = mock(RiskSettingsService.class);
        FeeProvider fees = mock(FeeProvider.class);
        trading = mock(TradingSettingsService.class);
        telegram = mock(TelegramNotificationService.class);
        user = new UserAccountEntity("user", "hash", "User");
        when(users.findByUsername("user")).thenReturn(Optional.of(user));
        when(risk.get()).thenReturn(new RiskSettingsService.Settings(
                1_000_000_000L, 0.1, 0.8, 40, 3, 5_000, 5_000,
                1_000, 1, 120, "BALANCED", null));
        when(fees.buyFee(any(), any())).thenReturn(0.05);
        when(fees.sellFee(any(), any())).thenReturn(0.05);
        LiveTradingProperties live = new LiveTradingProperties(
                true, true, false, 12_000, 5_000, 6_000, 45, 2.0,
                true, 3_000, 1_800, 600, 120, 1);
        service = new TradeCycleService(cycles, orders, users, risk, fees, trading, telegram, live);
    }

    @Test
    void completesMatchedCycleAndCalculatesNetProfit() {
        TradeCycleEntity cycle = cycle("cycle-1");
        cycle.submitted("buy-1", "sell-1");
        LiveOrderEntity buy = order("UPBIT", "BUY", "buy-1", "100", "1", "done");
        LiveOrderEntity sell = order("BITHUMB", "SELL", "sell-1", "101", "1", "done");
        when(cycles.findTop100ByStatusOrderByCreatedAtAsc(TradeCycleEntity.Status.SUBMITTED))
                .thenReturn(List.of(cycle));
        when(orders.findByOrderId("buy-1")).thenReturn(Optional.of(buy));
        when(orders.findByOrderId("sell-1")).thenReturn(Optional.of(sell));

        service.reconcile();

        assertThat(cycle.getStatus()).isEqualTo(TradeCycleEntity.Status.COMPLETED);
        assertThat(cycle.getRealizedProfitKrw()).isEqualByComparingTo("0.8995");
    }

    @Test
    void stopsTradingWhenFilledQuantitiesDoNotMatch() {
        TradeCycleEntity cycle = cycle("cycle-2");
        cycle.submitted("buy-2", "sell-2");
        when(cycles.findTop100ByStatusOrderByCreatedAtAsc(TradeCycleEntity.Status.SUBMITTED))
                .thenReturn(List.of(cycle));
        when(orders.findByOrderId("buy-2")).thenReturn(Optional.of(
                order("UPBIT", "BUY", "buy-2", "100", "1", "done")));
        when(orders.findByOrderId("sell-2")).thenReturn(Optional.of(
                order("BITHUMB", "SELL", "sell-2", "101", "0.9", "done")));

        service.reconcile();

        assertThat(cycle.getStatus()).isEqualTo(TradeCycleEntity.Status.MISMATCH);
        verify(trading).emergencyStop("user");
        verify(telegram).notifyAutoTradingFailure(any(), any(), any(), any(), any());
    }

    private TradeCycleEntity cycle(String id) {
        return new TradeCycleEntity(id, user, "BTC/KRW", "UPBIT", "BITHUMB",
                BigDecimal.valueOf(5_000), BigDecimal.valueOf(50));
    }

    private LiveOrderEntity order(String exchange, String side, String id,
                                  String price, String quantity, String status) {
        return new LiveOrderEntity(user, exchange, "BTC/KRW", side, id,
                BigDecimal.ZERO, new BigDecimal(quantity), new BigDecimal(price), status, "AUTO_ARBITRAGE");
    }
}
