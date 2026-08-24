package com.coin.arbitrage.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "portfolio_plan_settings", indexes = @Index(name = "idx_portfolio_plan_user", columnList = "user_id"))
public class PortfolioPlanSettingsEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserAccountEntity user;

    @Column(nullable = false)
    private int targetSymbolCount;
    @Column(nullable = false)
    private long targetKrwPerSymbolPerExchange;
    @Column(nullable = false)
    private long cashReserveKrwPerExchange;
    @Column(nullable = false)
    private long maxSeedBuyKrw;
    @Column(nullable = false)
    private long seedBuyCooldownSeconds;
    private Integer strategyVersion;
    @Column(nullable = false)
    private Instant updatedAt;

    protected PortfolioPlanSettingsEntity() { }

    public PortfolioPlanSettingsEntity(UserAccountEntity user) {
        this.user = user;
        this.targetSymbolCount = 5;
        this.targetKrwPerSymbolPerExchange = 12_000;
        this.cashReserveKrwPerExchange = 30_000;
        this.maxSeedBuyKrw = 12_000;
        this.seedBuyCooldownSeconds = 600;
        this.strategyVersion = 5;
        this.updatedAt = Instant.now();
    }

    public void update(int targetSymbolCount, long targetKrwPerSymbolPerExchange,
                       long cashReserveKrwPerExchange, long maxSeedBuyKrw,
                       long seedBuyCooldownSeconds) {
        this.targetSymbolCount = targetSymbolCount;
        this.targetKrwPerSymbolPerExchange = targetKrwPerSymbolPerExchange;
        this.cashReserveKrwPerExchange = cashReserveKrwPerExchange;
        this.maxSeedBuyKrw = maxSeedBuyKrw;
        this.seedBuyCooldownSeconds = seedBuyCooldownSeconds;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public UserAccountEntity getUser() { return user; }
    public int getTargetSymbolCount() { return targetSymbolCount; }
    public long getTargetKrwPerSymbolPerExchange() { return targetKrwPerSymbolPerExchange; }
    public long getCashReserveKrwPerExchange() { return cashReserveKrwPerExchange; }
    public long getMaxSeedBuyKrw() { return maxSeedBuyKrw; }
    public long getSeedBuyCooldownSeconds() { return seedBuyCooldownSeconds; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getStrategyVersion() { return strategyVersion == null ? 0 : strategyVersion; }
    public void markStrategyVersion(int value) { this.strategyVersion = value; }
}
