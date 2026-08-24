package com.coin.arbitrage.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "notification_settings", indexes = @Index(name = "idx_notification_user", columnList = "user_id"))
public class NotificationSettingsEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserAccountEntity user;
    @Column(length = 80)
    private String telegramChatId;
    @Column(nullable = false)
    private boolean telegramEnabled;
    @Column(nullable = false)
    private boolean opportunityEnabled;
    @Column(nullable = false)
    private boolean rebalanceEnabled;
    @Column(nullable = false)
    private boolean liveCandidateEnabled;
    @Column(nullable = false)
    private long cooldownSeconds;
    @Column(length = 30)
    private String activeKrwRebalanceRoute;
    @Column(nullable = false)
    private Instant updatedAt;

    protected NotificationSettingsEntity() { }

    public NotificationSettingsEntity(UserAccountEntity user) {
        this.user = user;
        this.telegramEnabled = false;
        this.opportunityEnabled = false;
        this.rebalanceEnabled = true;
        this.liveCandidateEnabled = true;
        this.cooldownSeconds = 600;
        this.updatedAt = Instant.now();
    }

    public void update(String telegramChatId, boolean telegramEnabled, boolean opportunityEnabled,
                       boolean rebalanceEnabled, boolean liveCandidateEnabled, long cooldownSeconds) {
        this.telegramChatId = telegramChatId == null ? "" : telegramChatId.trim();
        this.telegramEnabled = telegramEnabled;
        this.opportunityEnabled = opportunityEnabled;
        this.rebalanceEnabled = rebalanceEnabled;
        this.liveCandidateEnabled = liveCandidateEnabled;
        this.cooldownSeconds = Math.max(0, cooldownSeconds);
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public UserAccountEntity getUser() { return user; }
    public String getTelegramChatId() { return telegramChatId == null ? "" : telegramChatId; }
    public boolean isTelegramEnabled() { return telegramEnabled; }
    public boolean isOpportunityEnabled() { return opportunityEnabled; }
    public boolean isRebalanceEnabled() { return rebalanceEnabled; }
    public boolean isLiveCandidateEnabled() { return liveCandidateEnabled; }
    public long getCooldownSeconds() { return cooldownSeconds; }
    public Instant getUpdatedAt() { return updatedAt; }

    public boolean claimKrwRebalanceRoute(String route) {
        String normalized = route == null ? "" : route.trim().toUpperCase();
        if (normalized.isBlank() || normalized.equals(activeKrwRebalanceRoute)) return false;
        activeKrwRebalanceRoute = normalized;
        return true;
    }

    public void clearKrwRebalanceRoute() {
        activeKrwRebalanceRoute = null;
    }
}
