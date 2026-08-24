package com.coin.arbitrage.persistence;

import com.coin.arbitrage.domain.ArbitrageOpportunity;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "arbitrage_opportunities", indexes = @Index(name = "idx_opportunity_detected", columnList = "detectedAt"))
public class OpportunityEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String symbol;
    private String buyExchange;
    private String sellExchange;
    private double buyPrice;
    private double sellPrice;
    private double baseAmount;
    private double investmentKrw;
    private double rawProfitPercent;
    private double netProfitPercent;
    private double expectedProfitKrw;
    private double buyQuoteVolume;
    private double sellQuoteVolume;
    private Instant detectedAt;

    protected OpportunityEntity() {
    }

    public OpportunityEntity(ArbitrageOpportunity value) {
        symbol = value.symbol();
        buyExchange = value.buyExchange();
        sellExchange = value.sellExchange();
        buyPrice = value.buyPrice();
        sellPrice = value.sellPrice();
        baseAmount = value.baseAmount();
        investmentKrw = value.investmentKrw();
        rawProfitPercent = value.rawProfitPercent();
        netProfitPercent = value.netProfitPercent();
        expectedProfitKrw = value.expectedProfitKrw();
        buyQuoteVolume = value.buyQuoteVolume();
        sellQuoteVolume = value.sellQuoteVolume();
        detectedAt = value.detectedAt();
    }

    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public String getBuyExchange() { return buyExchange; }
    public String getSellExchange() { return sellExchange; }
    public double getBuyPrice() { return buyPrice; }
    public double getSellPrice() { return sellPrice; }
    public double getBaseAmount() { return baseAmount; }
    public double getInvestmentKrw() { return investmentKrw; }
    public double getRawProfitPercent() { return rawProfitPercent; }
    public double getNetProfitPercent() { return netProfitPercent; }
    public double getExpectedProfitKrw() { return expectedProfitKrw; }
    public double getExpectedProfitPerCoinKrw() {
        return baseAmount <= 0 ? 0 : expectedProfitKrw / baseAmount;
    }
    public double getBuyQuoteVolume() { return buyQuoteVolume; }
    public double getSellQuoteVolume() { return sellQuoteVolume; }
    public Instant getDetectedAt() { return detectedAt; }
}
