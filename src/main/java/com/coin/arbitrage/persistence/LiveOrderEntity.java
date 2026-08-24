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
@Table(name = "live_orders", indexes = {
        @Index(name = "idx_live_order_user_created", columnList = "user_id,createdAt"),
        @Index(name = "idx_live_order_order_id", columnList = "orderId")
})
public class LiveOrderEntity {
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
    @Column(nullable = false, length = 12)
    private String side;
    @Column(nullable = false, length = 100)
    private String orderId;
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal requestedKrw;
    @Column(nullable = false, precision = 28, scale = 12)
    private BigDecimal quantity;
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal executedPrice;
    @Column(nullable = false, length = 30)
    private String status;
    @Column(nullable = false, length = 40)
    private String source;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected LiveOrderEntity() { }

    public LiveOrderEntity(UserAccountEntity user, String exchange, String symbol, String side, String orderId,
                           BigDecimal requestedKrw, BigDecimal quantity, BigDecimal executedPrice,
                           String status, String source) {
        this.user = user;
        this.exchange = exchange;
        this.symbol = symbol;
        this.side = side;
        this.orderId = orderId;
        this.requestedKrw = requestedKrw;
        this.quantity = quantity;
        this.executedPrice = executedPrice;
        this.status = status;
        this.source = source;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public UserAccountEntity getUser() { return user; }
    public String getExchange() { return exchange; }
    public String getSymbol() { return symbol; }
    public String getSide() { return side; }
    public String getOrderId() { return orderId; }
    public BigDecimal getRequestedKrw() { return requestedKrw; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getExecutedPrice() { return executedPrice; }
    public String getStatus() { return status; }
    public String getSource() { return source; }
    public Instant getCreatedAt() { return createdAt; }

    public void updateExecution(String status, BigDecimal executedQuantity, BigDecimal executedPrice) {
        this.status = status == null || status.isBlank() ? this.status : status;
        if (executedQuantity != null && executedQuantity.compareTo(BigDecimal.ZERO) >= 0) {
            this.quantity = executedQuantity;
        }
        if (executedPrice != null && executedPrice.compareTo(BigDecimal.ZERO) > 0) {
            this.executedPrice = executedPrice;
        }
    }
}
