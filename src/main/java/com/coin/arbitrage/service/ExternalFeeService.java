package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.ExternalFeeEntity;
import com.coin.arbitrage.persistence.ExternalFeeRepository;
import com.coin.arbitrage.persistence.NotificationSettingsRepository;
import com.coin.arbitrage.persistence.UserAccountEntity;
import com.coin.arbitrage.persistence.UserAccountRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExternalFeeService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final ExternalFeeRepository fees;
    private final NotificationSettingsRepository settings;
    private final UserAccountRepository users;

    public ExternalFeeService(ExternalFeeRepository fees, NotificationSettingsRepository settings,
                              UserAccountRepository users) {
        this.fees = fees;
        this.settings = settings;
        this.users = users;
    }

    @Transactional
    public RecordResult record(String chatId, long updateId, long amountKrw) {
        if (amountKrw <= 0 || amountKrw > 10_000_000) {
            throw new IllegalArgumentException("수수료는 1원 이상 1,000만원 이하로 입력해 주세요.");
        }
        var notification = settings.findByTelegramChatIdAndTelegramEnabledTrue(chatId)
                .orElseThrow(() -> new IllegalArgumentException("등록된 텔레그램 계정을 찾을 수 없습니다."));
        String username = notification.getUser().getUsername();
        LocalDate today = LocalDate.now(SEOUL);
        if (!fees.existsByTelegramUpdateId(updateId)) {
            fees.save(new ExternalFeeEntity(notification.getUser(), updateId,
                    BigDecimal.valueOf(amountKrw), today));
        }
        return new RecordResult(username, fees.sumByUsernameAndFeeDate(username, today));
    }

    @Transactional
    public RecordResult recordForUsername(String username, long amountKrw) {
        if (amountKrw <= 0 || amountKrw > 10_000_000) {
            throw new IllegalArgumentException("수수료는 1원 이상 1,000만원 이하로 입력해 주세요.");
        }
        UserAccountEntity user = users.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자 계정을 찾을 수 없습니다."));
        LocalDate today = LocalDate.now(SEOUL);
        long syntheticId = nextSyntheticUpdateId();
        fees.save(new ExternalFeeEntity(user, syntheticId, BigDecimal.valueOf(amountKrw), today));
        return new RecordResult(username, fees.sumByUsernameAndFeeDate(username, today));
    }

    private long nextSyntheticUpdateId() {
        long value;
        do {
            value = -Math.abs(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        } while (fees.existsByTelegramUpdateId(value));
        return value;
    }

    public record RecordResult(String username, BigDecimal todayTotalKrw) { }
}
