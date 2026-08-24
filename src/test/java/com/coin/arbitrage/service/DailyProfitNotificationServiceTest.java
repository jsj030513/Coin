package com.coin.arbitrage.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coin.arbitrage.persistence.TradeCycleRepository;
import com.coin.arbitrage.persistence.ExternalFeeRepository;
import com.coin.arbitrage.persistence.PrincipalDepositRepository;
import com.coin.arbitrage.persistence.ProfitAdjustmentRepository;
import com.coin.arbitrage.persistence.UserAccountEntity;
import com.coin.arbitrage.persistence.UserAccountRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class DailyProfitNotificationServiceTest {
    @Test
    void sendsTodayAndTotalProfit() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        TradeCycleRepository cycles = mock(TradeCycleRepository.class);
        ProfitAdjustmentRepository adjustments = mock(ProfitAdjustmentRepository.class);
        ExternalFeeRepository fees = mock(ExternalFeeRepository.class);
        PrincipalDepositRepository deposits = mock(PrincipalDepositRepository.class);
        TelegramNotificationService telegram = mock(TelegramNotificationService.class);
        UserAccountEntity user = mock(UserAccountEntity.class);
        when(telegram.configured()).thenReturn(true);
        when(user.getUsername()).thenReturn("user");
        when(users.findAll()).thenReturn(List.of(user));
        when(cycles.sumRealizedProfitSince(eq("user"), any())).thenReturn(BigDecimal.valueOf(145));
        when(cycles.sumRealizedProfit("user")).thenReturn(BigDecimal.valueOf(312));
        when(adjustments.sumByUsernameSince(eq("user"), any())).thenReturn(BigDecimal.valueOf(-20));
        when(adjustments.sumByUsername("user")).thenReturn(BigDecimal.valueOf(-35));
        when(fees.sumByUsernameAndFeeDate(eq("user"), any())).thenReturn(BigDecimal.valueOf(1000));
        when(fees.sumByUsername("user")).thenReturn(BigDecimal.valueOf(1000));
        when(deposits.sumByUsername("user")).thenReturn(BigDecimal.valueOf(220000));

        new DailyProfitNotificationService(users, cycles, adjustments, fees, deposits, telegram).send();

        verify(telegram).sendDailyProfitSummary("user", BigDecimal.valueOf(125), BigDecimal.valueOf(277),
                BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), BigDecimal.valueOf(220000));
    }
}
