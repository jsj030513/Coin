package com.coin.arbitrage.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "exchange_connections",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_exchange", columnNames = {"user_id", "exchange_name"}),
        indexes = @Index(name = "idx_connection_user", columnList = "user_id"))
public class ExchangeConnectionEntity {
    public enum Exchange { UPBIT, BITHUMB, COINONE, KORBIT }
    public enum Status { SAVED, VERIFIED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccountEntity user;
    @Enumerated(EnumType.STRING)
    @Column(name = "exchange_name", nullable = false, length = 20)
    private Exchange exchange;
    @Column(nullable = false, length = 1200)
    private String encryptedAccessKey;
    @Column(nullable = false, length = 2400)
    private String encryptedSecretKey;
    @Column(nullable = false, length = 20)
    private String keyFingerprint;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;
    private Instant lastVerifiedAt;
    @Column(length = 300)
    private String lastError;
    private int assetCount;
    @Column(length = 30)
    private String orderReadPermission;
    @Column(length = 30)
    private String orderCreatePermission;
    private Double buyFeePercent;
    private Double sellFeePercent;
    private Instant feeCheckedAt;
    private Instant feeChangedAt;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected ExchangeConnectionEntity() { }

    public ExchangeConnectionEntity(UserAccountEntity user, Exchange exchange, String encryptedAccessKey,
                                    String encryptedSecretKey, String keyFingerprint) {
        this.user = user;
        this.exchange = exchange;
        this.createdAt = Instant.now();
        updateCredentials(encryptedAccessKey, encryptedSecretKey, keyFingerprint);
    }

    public void updateCredentials(String encryptedAccessKey, String encryptedSecretKey, String keyFingerprint) {
        this.encryptedAccessKey = encryptedAccessKey;
        this.encryptedSecretKey = encryptedSecretKey;
        this.keyFingerprint = keyFingerprint;
        this.status = Status.SAVED;
        this.lastVerifiedAt = null;
        this.lastError = null;
        this.assetCount = 0;
        this.orderReadPermission = "UNKNOWN";
        this.orderCreatePermission = "UNKNOWN";
        this.buyFeePercent = null;
        this.sellFeePercent = null;
        this.updatedAt = Instant.now();
    }

    public void verified(int assetCount, String orderReadPermission, String orderCreatePermission,
                         Double buyFeePercent, Double sellFeePercent) {
        this.status = Status.VERIFIED;
        this.lastVerifiedAt = Instant.now();
        this.lastError = null;
        this.assetCount = assetCount;
        this.orderReadPermission = orderReadPermission;
        this.orderCreatePermission = orderCreatePermission;
        observeFees(buyFeePercent, sellFeePercent);
        this.updatedAt = Instant.now();
    }

    public void failed(String message) {
        this.status = Status.FAILED;
        this.lastVerifiedAt = Instant.now();
        this.lastError = message == null ? "인증 요청에 실패했습니다." : message.substring(0, Math.min(300, message.length()));
        this.assetCount = 0;
        this.orderReadPermission = "UNKNOWN";
        this.orderCreatePermission = "UNKNOWN";
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public UserAccountEntity getUser() { return user; }
    public Exchange getExchange() { return exchange; }
    public String getEncryptedAccessKey() { return encryptedAccessKey; }
    public String getEncryptedSecretKey() { return encryptedSecretKey; }
    public String getKeyFingerprint() { return keyFingerprint; }
    public Status getStatus() { return status; }
    public Instant getLastVerifiedAt() { return lastVerifiedAt; }
    public String getLastError() { return lastError; }
    public int getAssetCount() { return assetCount; }
    public String getOrderReadPermission() { return orderReadPermission == null ? "UNKNOWN" : orderReadPermission; }
    public String getOrderCreatePermission() { return orderCreatePermission == null ? "UNKNOWN" : orderCreatePermission; }
    public Double getBuyFeePercent() { return buyFeePercent; }
    public Double getSellFeePercent() { return sellFeePercent; }
    public Instant getFeeCheckedAt() { return feeCheckedAt; }
    public Instant getFeeChangedAt() { return feeChangedAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public boolean observeFees(Double buyFeePercent, Double sellFeePercent) {
        if (buyFeePercent == null && sellFeePercent == null) return false;
        boolean changed = (this.buyFeePercent != null && buyFeePercent != null
                && Math.abs(this.buyFeePercent-buyFeePercent)>0.0000001)
                || (this.sellFeePercent != null && sellFeePercent != null
                && Math.abs(this.sellFeePercent-sellFeePercent)>0.0000001);
        if (buyFeePercent != null) this.buyFeePercent=buyFeePercent;
        if (sellFeePercent != null) this.sellFeePercent=sellFeePercent;
        this.feeCheckedAt=Instant.now();
        if (changed) this.feeChangedAt=this.feeCheckedAt;
        this.updatedAt=Instant.now();
        return changed;
    }
}
