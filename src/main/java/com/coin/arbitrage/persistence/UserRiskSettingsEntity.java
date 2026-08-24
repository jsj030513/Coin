package com.coin.arbitrage.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_risk_settings")
public class UserRiskSettingsEntity {
    @Id private Long userId;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @MapsId @JoinColumn(name = "user_id")
    private UserAccountEntity user;
    private long minQuoteVolume24h; private double feePercent; private double minProfitPercent;
    private double minExpectedProfitKrw; private double maxProfitPercent; private long orderAmountKrw;
    private long maxOrderAmountKrw; private long dailyMaxLossKrw; private int maxConcurrentPositions;
    private long opportunityCooldownSeconds; private String profileName; private Instant updatedAt;
    protected UserRiskSettingsEntity() { }
    public UserRiskSettingsEntity(UserAccountEntity user) { this.user = user; }
    public void update(long volume, double fee, double minProfit, double minExpected, double maxProfit,
                       long order, long maxOrder, long dailyLoss, int positions, long cooldown, String profile) {
        minQuoteVolume24h=volume; feePercent=fee; minProfitPercent=minProfit; minExpectedProfitKrw=minExpected;
        maxProfitPercent=maxProfit; orderAmountKrw=order; maxOrderAmountKrw=maxOrder; dailyMaxLossKrw=dailyLoss;
        maxConcurrentPositions=positions; opportunityCooldownSeconds=cooldown; profileName=profile; updatedAt=Instant.now();
    }
    public UserAccountEntity getUser(){return user;} public long getMinQuoteVolume24h(){return minQuoteVolume24h;}
    public double getFeePercent(){return feePercent;} public double getMinProfitPercent(){return minProfitPercent;}
    public double getMinExpectedProfitKrw(){return minExpectedProfitKrw;} public double getMaxProfitPercent(){return maxProfitPercent;}
    public long getOrderAmountKrw(){return orderAmountKrw;} public long getMaxOrderAmountKrw(){return maxOrderAmountKrw;}
    public long getDailyMaxLossKrw(){return dailyMaxLossKrw;} public int getMaxConcurrentPositions(){return maxConcurrentPositions;}
    public long getOpportunityCooldownSeconds(){return opportunityCooldownSeconds;} public String getProfileName(){return profileName;}
    public Instant getUpdatedAt(){return updatedAt;}
}
