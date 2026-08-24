package com.coin.arbitrage.config;

import com.coin.arbitrage.persistence.UserAccountRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import com.coin.arbitrage.service.LoginAttemptService;
import com.coin.arbitrage.service.TelegramNotificationService;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    UserDetailsService userDetailsService(UserAccountRepository users) {
        return username -> users.findByUsername(username)
                .map(value -> User.withUsername(value.getUsername())
                        .password(value.getPasswordHash()).roles(value.getRole())
                        .disabled(!value.isApproved()).accountLocked(value.isLocked()).build())
                .orElseThrow();
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, LoginRateLimitFilter loginRateLimit,
                                            LoginAttemptService loginAttempts,
                                            SessionRegistry sessionRegistry, AdminTotpFilter adminTotpFilter,
                                            TelegramNotificationService telegram, UserAccountRepository users) throws Exception {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Strict").path("/"));
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/login.html", "/register", "/register.html",
                                "/recover", "/recover.html", "/api/auth/register", "/api/auth/recovery/**",
                                "/css/**", "/js/auth.js", "/js/security.js", "/actuator/health", "/error").permitAll()
                        .requestMatchers("/h2-console/**").denyAll()
                        .requestMatchers("/admin", "/admin.html", "/api/admin/**", "/js/admin.js").hasRole("ADMIN")
                        .requestMatchers("/admin-2fa", "/admin-2fa.html", "/js/admin-2fa.js").hasRole("ADMIN")
                        .requestMatchers("/api/auth/**").authenticated()
                        .requestMatchers("/", "/index.html", "/my", "/my.html", "/accounts", "/accounts.html",
                                "/live-orders", "/live-orders.html", "/security", "/security.html",
                                "/api/**", "/js/app.js", "/js/my.js", "/js/accounts.js", "/js/live-orders.js", "/js/safety.js").hasRole("USER")
                        .anyRequest().authenticated())
                .addFilterBefore(loginRateLimit, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(adminTotpFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .formLogin(form -> form.loginPage("/login").loginProcessingUrl("/login")
                        .successHandler((request, response, authentication) -> {
                            loginAttempts.succeeded(LoginRateLimitFilter.clientIp(request), authentication.getName());
                            boolean admin = authentication.getAuthorities().stream()
                                    .anyMatch(value -> "ROLE_ADMIN".equals(value.getAuthority()));
                            response.sendRedirect(admin ? "/admin-2fa" : "/");
                        })
                        .failureHandler((request, response, error) -> {
                            loginAttempts.failed(LoginRateLimitFilter.clientIp(request), request.getParameter("username"));
                            users.findByUsername(request.getParameter("username") == null ? "" : request.getParameter("username").toLowerCase())
                                    .filter(value -> "ADMIN".equals(value.getRole()))
                                    .ifPresent(value -> telegram.notifyAdminLoginFailure(value.getUsername(), LoginRateLimitFilter.clientIp(request)));
                            response.sendRedirect("/login?error");
                        }).permitAll())
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout"))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, denied) -> {
                    if (request.getRequestURI().startsWith("/api/")) { response.sendError(403); return; }
                    var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                    boolean admin = authentication != null && authentication.getAuthorities().stream()
                            .anyMatch(value -> "ROLE_ADMIN".equals(value.getAuthority()));
                    var session = request.getSession(false);
                    if (session != null) session.invalidate();
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                    response.sendRedirect(admin ? "/login?roleSwitch=user" : "/login?roleSwitch=admin");
                }))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/login", "/logout"))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
                                "img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; " +
                                "base-uri 'self'; frame-ancestors 'none'; form-action 'self'"))
                        .permissionsPolicyHeader(policy -> policy.policy(
                                "camera=(), microphone=(), geolocation=(), payment=(), usb=()")))
                .sessionManagement(session -> session.sessionFixation().migrateSession()
                        .invalidSessionUrl("/login?expired")
                        .maximumSessions(3).maxSessionsPreventsLogin(false).sessionRegistry(sessionRegistry));
        return http.build();
    }
}
