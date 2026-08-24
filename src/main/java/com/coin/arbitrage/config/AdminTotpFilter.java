package com.coin.arbitrage.config;
import com.coin.arbitrage.service.AdminTotpService; import jakarta.servlet.*; import jakarta.servlet.http.*; import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder; import org.springframework.stereotype.Component; import org.springframework.web.filter.OncePerRequestFilter;
@Component public class AdminTotpFilter extends OncePerRequestFilter {
 public static final String VERIFIED="ADMIN_TOTP_VERIFIED"; private final AdminTotpService totp;
 public AdminTotpFilter(AdminTotpService totp){this.totp=totp;}
 protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
  String path=req.getRequestURI();var auth=SecurityContextHolder.getContext().getAuthentication();boolean admin=auth!=null&&auth.isAuthenticated()&&auth.getAuthorities().stream().anyMatch(a->"ROLE_ADMIN".equals(a.getAuthority()));
  boolean protectedPath=path.equals("/admin")||path.equals("/admin.html")||path.startsWith("/api/admin/");boolean exempt=path.startsWith("/api/admin/2fa")||path.equals("/admin-2fa")||path.equals("/admin-2fa.html")||path.equals("/js/admin-2fa.js");
  if(admin&&protectedPath&&!exempt&&!Boolean.TRUE.equals(req.getSession().getAttribute(VERIFIED))){if(path.startsWith("/api/")){res.sendError(401,"관리자 2단계 인증이 필요합니다.");}else res.sendRedirect("/admin-2fa");return;}chain.doFilter(req,res);
 }
}
