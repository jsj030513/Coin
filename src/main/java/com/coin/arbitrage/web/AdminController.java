package com.coin.arbitrage.web;

import com.coin.arbitrage.persistence.*;
import com.coin.arbitrage.service.*;
import java.math.BigDecimal; import java.security.Principal; import java.util.List;
import org.springframework.web.bind.annotation.*;
import com.coin.arbitrage.exchange.ApiRequestMetrics;

@RestController @RequestMapping("/api/admin")
public class AdminController {
 private final UserAccountRepository users; private final ExchangeConnectionRepository connections;
 private final TradeCycleRepository cycles; private final TradingSettingsService trading;
 private final RiskSettingsService risk; private final AdminAuditRepository audits;
 private final InviteCodeRepository invites; private final UserAccountService userService;
 private final ExternalFeeRepository externalFees;
 private final DatabaseBackupService backups;
 private final SystemStatusService systemStatus;
 private final OperationStatusService operationStatus;
 public AdminController(UserAccountRepository users,ExchangeConnectionRepository connections,TradeCycleRepository cycles,
                        TradingSettingsService trading,RiskSettingsService risk,AdminAuditRepository audits,
                        InviteCodeRepository invites,UserAccountService userService,ExternalFeeRepository externalFees,DatabaseBackupService backups,SystemStatusService systemStatus,OperationStatusService operationStatus){
  this.users=users;this.connections=connections;this.cycles=cycles;this.trading=trading;this.risk=risk;this.audits=audits;this.invites=invites;this.userService=userService;this.externalFees=externalFees;this.backups=backups;this.systemStatus=systemStatus;
  this.operationStatus=operationStatus;
 }
 @GetMapping("/users") public List<UserView> users(){return users.findAll().stream().map(u->new UserView(u.getUsername(),u.getDisplayName(),u.getRole(),u.getCreatedAt(),
   connections.findByUserUsernameOrderByExchangeAsc(u.getUsername()).stream().map(c->new ConnectionView(c.getExchange().name(),c.getStatus().name(),c.getLastVerifiedAt())).toList(),
   trading.status(u.getUsername()),risk.get(u.getUsername()),cycles.sumRealizedProfit(u.getUsername()),externalFees.sumByUsername(u.getUsername()),
   cycles.countByUserUsernameAndStatusIn(u.getUsername(),List.of(TradeCycleEntity.Status.PENDING,TradeCycleEntity.Status.SUBMITTED)),u.isApproved(),u.isLocked(),systemStatus.status(u.getUsername()),
   "ADMIN".equals(u.getRole())?null:operationStatus.status(u.getUsername()))).toList();}
 @PostMapping("/invites") public InviteView invite(Principal admin){String code=java.util.UUID.randomUUID().toString();var row=invites.save(new InviteCodeEntity(code,admin.getName(),java.time.Instant.now().plusSeconds(86400)));audits.save(new AdminAuditEntity(admin.getName(),"-","INVITE_CREATE","24시간 초대코드 생성"));return new InviteView(row.getCode(),row.getExpiresAt());}
 @PostMapping("/users/{username}/approve") public void approve(Principal admin,@PathVariable String username){userService.approve(username);audits.save(new AdminAuditEntity(admin.getName(),username,"USER_APPROVE","사용자 승인"));}
 @PostMapping("/users/{username}/lock") public void lock(Principal admin,@PathVariable String username,@RequestParam boolean value){var target=users.findByUsername(username).orElseThrow();if("ADMIN".equals(target.getRole()))throw new IllegalArgumentException("관리자 계정은 이 화면에서 잠글 수 없습니다.");userService.lock(username,value);if(value)trading.emergencyStop(username);audits.save(new AdminAuditEntity(admin.getName(),username,value?"USER_LOCK":"USER_UNLOCK","계정 잠금 변경"));}
 @PostMapping("/users/{username}/stop") public TradingSettingsService.Status stop(Principal admin,@PathVariable String username){
 var result=trading.emergencyStop(username); audits.save(new AdminAuditEntity(admin.getName(),username,"EMERGENCY_STOP","관리자 비상정지")); return result; }
 @PostMapping("/emergency-stop") public void stopAll(Principal admin){users.findAll().stream().filter(u->!"ADMIN".equals(u.getRole())).forEach(u->trading.emergencyStop(u.getUsername()));audits.save(new AdminAuditEntity(admin.getName(),"ALL","GLOBAL_EMERGENCY_STOP","전체 자동매매 정지"));}
 @PutMapping("/users/{username}/settings") public RiskSettingsService.Settings settings(Principal admin,@PathVariable String username,@RequestBody RiskSettingsService.Settings value){
  var result=risk.update(username,value); audits.save(new AdminAuditEntity(admin.getName(),username,"RISK_SETTINGS_UPDATE","사용자별 리스크 설정 변경")); return result; }
 @GetMapping("/audits") public List<AdminAuditEntity> audits(){return audits.findTop100ByOrderByCreatedAtDesc();}
 @GetMapping("/backups") public List<String> backups(){return backups.list();}
 @GetMapping("/api-metrics") public ApiRequestMetrics.Snapshot apiMetrics(){return ApiRequestMetrics.snapshot();}
 @PostMapping("/backups") public String backup(Principal admin){String name=backups.create();audits.save(new AdminAuditEntity(admin.getName(),"SYSTEM","DATABASE_BACKUP",name));return name;}
 public record ConnectionView(String exchange,String status,java.time.Instant lastVerifiedAt){}
 public record UserView(String username,String displayName,String role,java.time.Instant createdAt,List<ConnectionView> connections,
                        TradingSettingsService.Status trading,RiskSettingsService.Settings risk,BigDecimal realizedProfitKrw,
                        BigDecimal externalFeeKrw,long openCycles,boolean approved,boolean locked,SystemStatusService.Status system,
                        OperationStatusService.Status operation){}
 public record InviteView(String code,java.time.Instant expiresAt){}
}
