package com.coin.arbitrage.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "trading_settings")
public class TradingSettingsEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserAccountEntity user;

    @Column(nullable = false)
    private boolean autoTradingEnabled;

    @Column(nullable = false)
    private Instant updatedAt;

    protected TradingSettingsEntity() { }

    public TradingSettingsEntity(UserAccountEntity user) {
        this.user = user;
        this.autoTradingEnabled = false;
        this.updatedAt = Instant.now();
    }

    public void setAutoTradingEnabled(boolean enabled) {
        this.autoTradingEnabled = enabled;
        this.updatedAt = Instant.now();
    }

    public boolean isAutoTradingEnabled() { return autoTradingEnabled; }
    public Instant getUpdatedAt() { return updatedAt; }
}
