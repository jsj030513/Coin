package com.coin.arbitrage.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationSettingsRepository extends JpaRepository<NotificationSettingsEntity, Long> {
    Optional<NotificationSettingsEntity> findByUserUsername(String username);
    Optional<NotificationSettingsEntity> findByTelegramChatIdAndTelegramEnabledTrue(String telegramChatId);
    List<NotificationSettingsEntity> findByTelegramEnabledTrueAndOpportunityEnabledTrue();
}
