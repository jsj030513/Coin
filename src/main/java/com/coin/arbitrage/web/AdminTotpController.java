package com.coin.arbitrage.web;
import com.coin.arbitrage.config.AdminTotpFilter; import com.coin.arbitrage.service.AdminTotpService; import jakarta.servlet.http.HttpSession; import java.security.Principal; import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin/2fa") public class AdminTotpController {
 private final AdminTotpService totp; public AdminTotpController(AdminTotpService totp){this.totp=totp;}
 @GetMapping("/status") public Map<String,Boolean> status(Principal p,HttpSession s){return Map.of("enabled",totp.enabled(p.getName()),"verified",Boolean.TRUE.equals(s.getAttribute(AdminTotpFilter.VERIFIED)));}
 @PostMapping("/setup") public AdminTotpService.Setup setup(Principal p){return totp.setup(p.getName());}
 @PostMapping("/verify") public Map<String,Boolean> verify(Principal p,HttpSession s,@RequestBody Map<String,String> body){boolean enabled=totp.enabled(p.getName());boolean ok=enabled?totp.verify(p.getName(),body.get("code")):totp.verifyAndEnable(p.getName(),body.get("code"));if(ok)s.setAttribute(AdminTotpFilter.VERIFIED,true);return Map.of("verified",ok);}
}
