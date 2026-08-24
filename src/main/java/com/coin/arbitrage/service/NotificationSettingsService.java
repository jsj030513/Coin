package com.coin.arbitrage.service;

import com.coin.arbitrage.persistence.NotificationSettingsEntity;
import com.coin.arbitrage.persistence.NotificationSettingsRepository;
import com.coin.arbitrage.persistence.UserAccountEntity;
import com.coin.arbitrage.persistence.UserAccountRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationSettingsService {
    private final NotificationSettingsRepository settings;
    private final UserAccountRepository users;

    public NotificationSettingsService(NotificationSettingsRepository settings, UserAccountRepository users) {
        this.settings = settings;
        this.users = users;
    }

    @Transactional
    public Settings get(String username) {
        return toDto(getOrCreate(username));
    }

    @Transactional
    public Settings update(String username, UpdateRequest request) {
        NotificationSettingsEntity entity = getOrCreate(username);
        entity.update(request.telegramChatId(), request.telegramEnabled(), request.opportunityEnabled(),
                request.rebalanceEnabled(), request.liveCandidateEnabled(), request.cooldownSeconds());
        return toDto(entity);
    }

    public boolean canSendTelegram(String username, Channel channel) {
        return settings.findByUserUsername(username)
                .filter(NotificationSettingsEntity::isTelegramEnabled)
                .filter(value -> !value.getTelegramChatId().isBlank())
                .filter(value -> switch (channel) {
                    case OPPORTUNITY -> value.isOpportunityEnabled();
                    case REBALANCE -> value.isRebalanceEnabled();
                    case LIVE_CANDIDATE -> value.isLiveCandidateEnabled();
                    case TEST -> true;
                })
                .isPresent();
    }

    public String telegramChatId(String username) {
        return settings.findByUserUsername(username)
                .map(NotificationSettingsEntity::getTelegramChatId)
                .orElse("");
    }

    @Transactional
    public boolean claimKrwRebalanceAlert(String username, String fromExchange, String toExchange) {
        NotificationSettingsEntity entity = getOrCreate(username);
        return entity.claimKrwRebalanceRoute(fromExchange + "->" + toExchange);
    }

    @Transactional
    public void clearKrwRebalanceAlert(String username) {
        settings.findByUserUsername(username).ifPresent(NotificationSettingsEntity::clearKrwRebalanceRoute);
    }

    private NotificationSettingsEntity getOrCreate(String username) {
        return settings.findByUserUsername(username)
                .orElseGet(() -> {
                    UserAccountEntity user = users.findByUsername(username).orElseThrow();
                    return settings.save(new NotificationSettingsEntity(user));
                });
    }

    private static Settings toDto(NotificationSettingsEntity entity) {
        return new Settings(entity.getTelegramChatId(), entity.isTelegramEnabled(),
                entity.isOpportunityEnabled(), entity.isRebalanceEnabled(),
                entity.isLiveCandidateEnabled(), entity.getCooldownSeconds(), entity.getUpdatedAt());
    }

    public enum Channel { OPPORTUNITY, REBALANCE, LIVE_CANDIDATE, TEST }

    public record UpdateRequest(String telegramChatId, boolean telegramEnabled, boolean opportunityEnabled,
                                boolean rebalanceEnabled, boolean liveCandidateEnabled, long cooldownSeconds) { }

    public record Settings(String telegramChatId, boolean telegramEnabled, boolean opportunityEnabled,
                           boolean rebalanceEnabled, boolean liveCandidateEnabled,
                           long cooldownSeconds, Instant updatedAt) { }
}
