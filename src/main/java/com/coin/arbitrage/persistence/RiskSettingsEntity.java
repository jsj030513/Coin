package com.coin.arbitrage.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "risk_settings")
public class RiskSettingsEntity {
    @Id
    private Long id;
    private long minQuoteVolume24h;
    private double feePercent;
    private double minProfitPercent;
    private Double minExpectedProfitKrw;
    private double maxProfitPercent;
    private long orderAmountKrw;
    private long maxOrderAmountKrw;
    private long dailyMaxLossKrw;
    private int maxConcurrentPositions;
    private long opportunityCooldownSeconds;
    private String profileName;
    private Integer strategyVersion;
    private Instant updatedAt;
    @Version
    private long version;

    protected RiskSettingsEntity() {
    }

    public RiskSettingsEntity(Long id, long minQuoteVolume24h, double feePercent,
                              double minProfitPercent, double minExpectedProfitKrw, double maxProfitPercent,
                              long orderAmountKrw, long maxOrderAmountKrw,
                              long dailyMaxLossKrw, int maxConcurrentPositions,
                              long opportunityCooldownSeconds, String profileName) {
        this.id = id;
        this.strategyVersion = 4;
        update(minQuoteVolume24h, feePercent, minProfitPercent, minExpectedProfitKrw, maxProfitPercent, orderAmountKrw,
                maxOrderAmountKrw, dailyMaxLossKrw, maxConcurrentPositions,
                opportunityCooldownSeconds, profileName);
    }

    public void update(long minQuoteVolume24h, double feePercent, double minProfitPercent,
                       double minExpectedProfitKrw, double maxProfitPercent, long orderAmountKrw, long maxOrderAmountKrw,
                       long dailyMaxLossKrw, int maxConcurrentPositions,
                       long opportunityCooldownSeconds, String profileName) {
        this.minQuoteVolume24h = minQuoteVolume24h;
        this.feePercent = feePercent;
        this.minProfitPercent = minProfitPercent;
        this.minExpectedProfitKrw = minExpectedProfitKrw;
        this.maxProfitPercent = maxProfitPercent;
        this.orderAmountKrw = orderAmountKrw;
        this.maxOrderAmountKrw = maxOrderAmountKrw;
        this.dailyMaxLossKrw = dailyMaxLossKrw;
        this.maxConcurrentPositions = maxConcurrentPositions;
        this.opportunityCooldownSeconds = opportunityCooldownSeconds;
        this.profileName = profileName;
        this.updatedAt = Instant.now();
    }

    public long getMinQuoteVolume24h() { return minQuoteVolume24h; }
    public double getFeePercent() { return feePercent; }
    public double getMinProfitPercent() { return minProfitPercent; }
    public double getMinExpectedProfitKrw() { return minExpectedProfitKrw == null ? 0 : minExpectedProfitKrw; }
    public double getMaxProfitPercent() { return maxProfitPercent; }
    public long getOrderAmountKrw() { return orderAmountKrw; }
    public long getMaxOrderAmountKrw() { return maxOrderAmountKrw; }
    public long getDailyMaxLossKrw() { return dailyMaxLossKrw; }
    public int getMaxConcurrentPositions() { return maxConcurrentPositions; }
    public long getOpportunityCooldownSeconds() { return opportunityCooldownSeconds; }
    public String getProfileName() { return profileName == null ? "USER_1" : profileName; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getStrategyVersion() { return strategyVersion == null ? 0 : strategyVersion; }
    public void markStrategyVersion(int value) { this.strategyVersion = value; }
}
