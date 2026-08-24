package com.coin.arbitrage.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "app_users")
public class UserAccountEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 40)
    private String username;
    @Column(nullable = false, length = 100)
    private String passwordHash;
    @Column(nullable = false, length = 50)
    private String displayName;
    @Column(length = 10)
    private String role = "USER";
    private Boolean approved;
    private Boolean locked;
    private Instant withdrawnAt;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected UserAccountEntity() { }

    public UserAccountEntity(String username, String passwordHash, String displayName) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = "USER";
        this.approved = false;
        this.locked = false;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public String getRole() { return role == null ? "USER" : role; }
    public boolean isApproved() { return approved == null || approved; }
    public boolean isLocked() { return locked != null && locked; }
    public Instant getWithdrawnAt() { return withdrawnAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
    public void promoteAdmin() { this.role = "ADMIN"; this.approved = true; this.locked = false; }
    public void approve() { this.approved = true; this.locked = false; }
    public void setLocked(boolean value) { this.locked = value; }
    public void withdraw(String disabledPasswordHash) {
        this.passwordHash = disabledPasswordHash; this.displayName = "탈퇴 사용자";
        this.approved = false; this.locked = true; this.withdrawnAt = Instant.now();
    }
}
