package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.TradeCycleRepository;
import com.coin.arbitrage.persistence.ExternalFeeRepository;
import com.coin.arbitrage.persistence.ProfitAdjustmentRepository;
import com.coin.arbitrage.persistence.PrincipalDepositRepository;
import com.coin.arbitrage.persistence.UserAccountRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DailyProfitNotificationService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final UserAccountRepository users;
    private final TradeCycleRepository cycles;
    private final ProfitAdjustmentRepository adjustments;
    private final ExternalFeeRepository fees;
    private final PrincipalDepositRepository deposits;
    private final TelegramNotificationService telegram;

    public DailyProfitNotificationService(UserAccountRepository users,
                                          TradeCycleRepository cycles,
                                          ProfitAdjustmentRepository adjustments,
                                          ExternalFeeRepository fees,
                                          PrincipalDepositRepository deposits,
                                          TelegramNotificationService telegram) {
        this.users = users;
        this.cycles = cycles;
        this.adjustments = adjustments;
        this.fees = fees;
        this.deposits = deposits;
        this.telegram = telegram;
    }

    @Scheduled(cron = "${telegram.daily-profit-cron:0 50 23 * * *}",
            zone = "${telegram.daily-profit-zone:Asia/Seoul}")
    public void send() {
        if (!telegram.configured()) return;
        Instant today = LocalDate.now(SEOUL).atStartOfDay(SEOUL).toInstant();
        users.findAll().forEach(user -> {
            String username = user.getUsername();
            BigDecimal todayProfit = cycles.sumRealizedProfitSince(username, today)
                    .add(adjustments.sumByUsernameSince(username, today));
            BigDecimal totalProfit = cycles.sumRealizedProfit(username)
                    .add(adjustments.sumByUsername(username));
            telegram.sendDailyProfitSummary(username, todayProfit, totalProfit,
                    fees.sumByUsernameAndFeeDate(username, LocalDate.now(SEOUL)),
                    fees.sumByUsername(username),
                    deposits.sumByUsername(username));
        });
    }
}
