package com.coin.arbitrage.persistence;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="admin_audit_log")
public class AdminAuditEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,length=40) private String adminUsername;
 @Column(nullable=false,length=40) private String targetUsername;
 @Column(nullable=false,length=60) private String action;
 @Column(nullable=false,length=500) private String detail;
 @Column(nullable=false,updatable=false) private Instant createdAt;
 protected AdminAuditEntity(){}
 public AdminAuditEntity(String admin,String target,String action,String detail){this.adminUsername=admin;this.targetUsername=target;this.action=action;this.detail=detail;this.createdAt=Instant.now();}
 public Long getId(){return id;} public String getAdminUsername(){return adminUsername;} public String getTargetUsername(){return targetUsername;}
 public String getAction(){return action;} public String getDetail(){return detail;} public Instant getCreatedAt(){return createdAt;}
}
