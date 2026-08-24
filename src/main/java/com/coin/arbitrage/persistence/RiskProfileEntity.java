package com.coin.arbitrage.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "risk_profiles")
public class RiskProfileEntity {
    @Id
    private String profileName;
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
    private Instant updatedAt;

    protected RiskProfileEntity() {
    }

    public RiskProfileEntity(String profileName, long minQuoteVolume24h, double feePercent,
                             double minProfitPercent, double minExpectedProfitKrw, double maxProfitPercent, long orderAmountKrw,
                             long maxOrderAmountKrw, long dailyMaxLossKrw,
                             int maxConcurrentPositions, long opportunityCooldownSeconds) {
        this.profileName = profileName;
        update(minQuoteVolume24h, feePercent, minProfitPercent, minExpectedProfitKrw, maxProfitPercent, orderAmountKrw,
                maxOrderAmountKrw, dailyMaxLossKrw, maxConcurrentPositions, opportunityCooldownSeconds);
    }

    public void update(long minQuoteVolume24h, double feePercent, double minProfitPercent,
                       double minExpectedProfitKrw, double maxProfitPercent, long orderAmountKrw, long maxOrderAmountKrw,
                       long dailyMaxLossKrw, int maxConcurrentPositions,
                       long opportunityCooldownSeconds) {
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
        this.updatedAt = Instant.now();
    }

    public String getProfileName() { return profileName; }
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
    public Instant getUpdatedAt() { return updatedAt; }
}
