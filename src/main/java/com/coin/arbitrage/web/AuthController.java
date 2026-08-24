package com.coin.arbitrage.web;

import com.coin.arbitrage.persistence.UserAccountEntity;
import com.coin.arbitrage.service.UserAccountService;
import com.coin.arbitrage.service.AccountRecoveryService;
import com.coin.arbitrage.service.SessionSecurityService;
import com.coin.arbitrage.service.TradingSettingsService;
import com.coin.arbitrage.persistence.TradeCycleRepository;
import com.coin.arbitrage.persistence.TradeCycleEntity;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserAccountService users;
    private final AccountRecoveryService recovery;
    private final SessionSecurityService sessions;
    private final TradingSettingsService trading;
    private final TradeCycleRepository cycles;

    public AuthController(UserAccountService users, AccountRecoveryService recovery, SessionSecurityService sessions,
                          TradingSettingsService trading, TradeCycleRepository cycles) {
        this.users = users;
        this.recovery = recovery;
        this.sessions = sessions;
        this.trading = trading;
        this.cycles = cycles;
    }

    @PostMapping("/withdraw")
    public Map<String, String> withdraw(Principal principal, @RequestBody WithdrawRequest request) {
        String username = principal.getName();
        long open = cycles.countByUserUsernameAndStatusIn(username,
                java.util.List.of(TradeCycleEntity.Status.PENDING, TradeCycleEntity.Status.SUBMITTED));
        if (open > 0) throw new IllegalArgumentException("체결 확인 중인 주문이 있어 지금은 탈퇴할 수 없습니다.");
        trading.emergencyStop(username);
        users.withdraw(username, request.password());
        sessions.expireAll(username);
        return Map.of("message", "계정이 탈퇴 처리되었습니다.");
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@RequestBody RegisterRequest request) {
        UserAccountEntity user = users.register(request.username(), request.password(), request.displayName(), request.inviteCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("username", user.getUsername()));
    }

    @GetMapping("/session")
    public SessionView session(Principal principal) {
        UserAccountEntity user = users.require(principal.getName());
        return new SessionView(user.getUsername(), user.getDisplayName());
    }

    @PutMapping("/password")
    public Map<String, String> changePassword(Principal principal, @RequestBody ChangePasswordRequest request) {
        users.changePassword(principal.getName(), request.currentPassword(), request.newPassword());
        sessions.expireAll(principal.getName());
        return Map.of("message", "비밀번호가 변경되었습니다.");
    }

    @PostMapping("/recovery/request")
    public ResponseEntity<Map<String, String>> requestRecovery() {
        recovery.requestCode();
        return ResponseEntity.accepted().body(Map.of("message", "등록된 텔레그램으로 복구 코드를 보냈습니다."));
    }

    @PostMapping("/recovery/reset")
    public Map<String, String> resetPassword(@RequestBody ResetPasswordRequest request) {
        String username = recovery.resetPassword(request.code(), request.newPassword());
        sessions.expireAll(username);
        return Map.of("message", "비밀번호가 재설정되었습니다.", "username", username);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }

    public record RegisterRequest(String username, String password, String displayName, String inviteCode) { }
    public record ChangePasswordRequest(String currentPassword, String newPassword) { }
    public record ResetPasswordRequest(String code, String newPassword) { }
    public record SessionView(String username, String displayName) { }
    public record WithdrawRequest(String password) { }
}
