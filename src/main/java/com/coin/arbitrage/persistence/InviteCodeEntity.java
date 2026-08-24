package com.coin.arbitrage.persistence;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="invite_codes") public class InviteCodeEntity {
 @Id @Column(length=36) private String code; @Column(nullable=false,length=40) private String createdBy;
 @Column(nullable=false) private Instant expiresAt; private Instant usedAt; @Column(length=40) private String usedBy;
 protected InviteCodeEntity(){} public InviteCodeEntity(String code,String createdBy,Instant expiresAt){this.code=code;this.createdBy=createdBy;this.expiresAt=expiresAt;}
 public String getCode(){return code;} public Instant getExpiresAt(){return expiresAt;} public Instant getUsedAt(){return usedAt;}
 public void use(String username){usedAt=Instant.now();usedBy=username;}
}
