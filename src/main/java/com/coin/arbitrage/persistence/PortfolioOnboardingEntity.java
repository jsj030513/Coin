package com.coin.arbitrage.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "portfolio_onboarding", indexes = @Index(name = "idx_onboarding_token", columnList = "decision_token", unique = true))
public class PortfolioOnboardingEntity {
    public enum Status { PENDING, WAITING_DECISION, KEEP_SELECTED, SELL_SELECTED, COMPLETE }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserAccountEntity user;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private Status status;
    @Column(name = "decision_token", length = 40, unique = true) private String decisionToken;
    @Column(nullable = false) private Instant updatedAt;

    protected PortfolioOnboardingEntity() { }
    public PortfolioOnboardingEntity(UserAccountEntity user) {
        this.user = user; this.status = Status.PENDING; this.updatedAt = Instant.now();
    }
    public void waiting(String token) { status=Status.WAITING_DECISION; decisionToken=token; updatedAt=Instant.now(); }
    public void decide(Status value) { status=value; updatedAt=Instant.now(); }
    public void complete() { status=Status.COMPLETE; updatedAt=Instant.now(); }
    public void resetPending() { status=Status.PENDING; decisionToken=null; updatedAt=Instant.now(); }
    public void consumeToken() { decisionToken=null; updatedAt=Instant.now(); }
    public Long getId(){return id;} public UserAccountEntity getUser(){return user;} public Status getStatus(){return status;}
    public String getDecisionToken(){return decisionToken;} public Instant getUpdatedAt(){return updatedAt;}
}
