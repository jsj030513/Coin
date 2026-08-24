package com.coin.arbitrage.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "profit_adjustments", indexes = {
        @Index(name = "idx_profit_adjustment_user_created", columnList = "user_id,createdAt")
})
public class ProfitAdjustmentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccountEntity user;

    @Column(nullable = false, length = 30)
    private String exchange;
    @Column(nullable = false, length = 30)
    private String symbol;
    @Column(nullable = false, length = 40)
    private String source;
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal amountKrw;
    @Column(nullable = false, precision = 28, scale = 12)
    private BigDecimal quantity;
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal proceedsKrw;
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal costBasisKrw;
    @Column(length = 300)
    private String detail;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ProfitAdjustmentEntity() { }

    public ProfitAdjustmentEntity(UserAccountEntity user, String exchange, String symbol, String source,
                                  BigDecimal amountKrw, BigDecimal quantity,
                                  BigDecimal proceedsKrw, BigDecimal costBasisKrw, String detail) {
        this.user = user;
        this.exchange = exchange;
        this.symbol = symbol;
        this.source = source;
        this.amountKrw = amountKrw;
        this.quantity = quantity;
        this.proceedsKrw = proceedsKrw;
        this.costBasisKrw = costBasisKrw;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public UserAccountEntity getUser() { return user; }
    public String getExchange() { return exchange; }
    public String getSymbol() { return symbol; }
    public String getSource() { return source; }
    public BigDecimal getAmountKrw() { return amountKrw; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getProceedsKrw() { return proceedsKrw; }
    public BigDecimal getCostBasisKrw() { return costBasisKrw; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
