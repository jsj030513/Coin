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
import java.time.LocalDate;

@Entity
@Table(name = "external_fees", indexes = {
        @Index(name = "idx_external_fee_user_date", columnList = "user_id,feeDate")
})
public class ExternalFeeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccountEntity user;
    @Column(nullable = false, unique = true)
    private long telegramUpdateId;
    @Column(nullable = false, precision = 20, scale = 0)
    private BigDecimal amountKrw;
    @Column(nullable = false)
    private LocalDate feeDate;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ExternalFeeEntity() { }

    public ExternalFeeEntity(UserAccountEntity user, long telegramUpdateId,
                             BigDecimal amountKrw, LocalDate feeDate) {
        this.user = user;
        this.telegramUpdateId = telegramUpdateId;
        this.amountKrw = amountKrw;
        this.feeDate = feeDate;
        this.createdAt = Instant.now();
    }
}
