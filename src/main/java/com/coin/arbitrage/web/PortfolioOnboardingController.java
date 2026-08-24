package com.coin.arbitrage.web;

import com.coin.arbitrage.service.PortfolioOnboardingService;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/portfolio-onboarding")
public class PortfolioOnboardingController {
    private final PortfolioOnboardingService service;

    public PortfolioOnboardingController(PortfolioOnboardingService service) {
        this.service = service;
    }

    @PostMapping("/initial-setup")
    public PortfolioOnboardingService.InitialSetupReport initialSetup(Principal principal) {
        return service.startInitialSetup(principal.getName());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<Map<String, String>> invalid(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
