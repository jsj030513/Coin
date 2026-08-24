package com.coin.arbitrage.persistence;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="admin_totp") public class AdminTotpEntity {
 @Id private Long userId; @OneToOne(fetch=FetchType.LAZY,optional=false) @MapsId @JoinColumn(name="user_id") private UserAccountEntity user;
 @Column(nullable=false,length=500) private String encryptedSecret; @Column(nullable=false) private boolean enabled; @Column(nullable=false) private Instant updatedAt;
 protected AdminTotpEntity(){} public AdminTotpEntity(UserAccountEntity user,String secret){this.user=user;this.encryptedSecret=secret;this.updatedAt=Instant.now();}
 public String getEncryptedSecret(){return encryptedSecret;} public boolean isEnabled(){return enabled;}
 public void replace(String value){encryptedSecret=value;enabled=false;updatedAt=Instant.now();} public void enable(){enabled=true;updatedAt=Instant.now();}
}
