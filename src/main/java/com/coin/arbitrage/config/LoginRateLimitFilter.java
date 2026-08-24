package com.coin.arbitrage.config;

import com.coin.arbitrage.service.LoginAttemptService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {
    private final LoginAttemptService attempts;

    public LoginRateLimitFilter(LoginAttemptService attempts) {
        this.attempts = attempts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/login".equals(request.getRequestURI())) {
            long retryAfter = attempts.retryAfterSeconds(clientIp(request), request.getParameter("username"));
            if (retryAfter > 0) {
                response.sendRedirect("/login?locked&retryAfter=" + retryAfter);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] chain = forwarded.split(",");
            return chain[chain.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
