package com.coin.arbitrage.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trade_cycles", indexes = {
        @Index(name = "idx_trade_cycle_user_created", columnList = "user_id,createdAt"),
        @Index(name = "idx_trade_cycle_status", columnList = "status")
})
public class TradeCycleEntity {
    public enum Status { PENDING, SUBMITTED, COMPLETED, MISMATCH, FAILED, TIMED_OUT }

    @Id
    @Column(length = 36)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccountEntity user;
    @Column(nullable = false, length = 30)
    private String symbol;
    @Column(nullable = false, length = 20)
    private String buyExchange;
    @Column(nullable = false, length = 20)
    private String sellExchange;
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal requestedKrw;
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal expectedProfitKrw;
    @Column(length = 100)
    private String buyOrderId;
    @Column(length = 100)
    private String sellOrderId;
    @Column(precision = 20, scale = 8)
    private BigDecimal realizedProfitKrw;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;
    @Column(length = 300)
    private String detail;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected TradeCycleEntity() { }

    public TradeCycleEntity(String id, UserAccountEntity user, String symbol,
                            String buyExchange, String sellExchange,
                            BigDecimal requestedKrw, BigDecimal expectedProfitKrw) {
        this.id = id;
        this.user = user;
        this.symbol = symbol;
        this.buyExchange = buyExchange;
        this.sellExchange = sellExchange;
        this.requestedKrw = requestedKrw;
        this.expectedProfitKrw = expectedProfitKrw;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void submitted(String buyOrderId, String sellOrderId) {
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.status = Status.SUBMITTED;
        this.updatedAt = Instant.now();
    }

    public void finish(Status status, BigDecimal realizedProfitKrw, String detail) {
        this.status = status;
        this.realizedProfitKrw = realizedProfitKrw;
        this.detail = detail;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public UserAccountEntity getUser() { return user; }
    public String getSymbol() { return symbol; }
    public String getBuyExchange() { return buyExchange; }
    public String getSellExchange() { return sellExchange; }
    public BigDecimal getRequestedKrw() { return requestedKrw; }
    public BigDecimal getExpectedProfitKrw() { return expectedProfitKrw; }
    public String getBuyOrderId() { return buyOrderId; }
    public String getSellOrderId() { return sellOrderId; }
    public BigDecimal getRealizedProfitKrw() { return realizedProfitKrw; }
    public Status getStatus() { return status; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
