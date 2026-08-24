package com.coin.arbitrage.web;

import com.coin.arbitrage.service.PortfolioPlanService;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/portfolio-plan")
public class PortfolioPlanController {
    private final PortfolioPlanService service;

    public PortfolioPlanController(PortfolioPlanService service) {
        this.service = service;
    }

    @GetMapping
    public PortfolioPlanService.PortfolioPlan plan(Principal principal) {
        return service.plan(principal.getName());
    }

    @GetMapping("/settings")
    public PortfolioPlanService.Settings settings(Principal principal) {
        return service.settings(principal.getName());
    }

    @PutMapping("/settings")
    public PortfolioPlanService.Settings updateSettings(
            Principal principal,
            @RequestBody PortfolioPlanService.SettingsRequest request) {
        return service.updateSettings(principal.getName(), request);
    }

    @PostMapping("/seed-buy")
    public PortfolioPlanService.SeedBuyDecision seedBuy(
            Principal principal,
            @RequestBody PortfolioPlanService.SeedBuyRequest request) {
        return service.approveSeedBuy(principal.getName(), request);
    }

    @PostMapping("/seed-buy-pair")
    public PortfolioPlanService.PairSeedBuyDecision seedBuyPair(
            Principal principal,
            @RequestBody PortfolioPlanService.PairSeedBuyRequest request) {
        return service.approvePairSeedBuy(principal.getName(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidSettings(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
