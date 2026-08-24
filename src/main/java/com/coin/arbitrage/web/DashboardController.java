package com.coin.arbitrage.web;

import com.coin.arbitrage.persistence.OpportunityEntity;
import com.coin.arbitrage.persistence.OpportunityRepository;
import com.coin.arbitrage.service.ArbitrageEngine;
import com.coin.arbitrage.service.RiskSettingsService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final ArbitrageEngine engine;
    private final OpportunityRepository opportunities;
    private final RiskSettingsService settingsService;

    public DashboardController(ArbitrageEngine engine, OpportunityRepository opportunities,
                               RiskSettingsService settingsService) {
        this.engine = engine;
        this.opportunities = opportunities;
        this.settingsService = settingsService;
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard(Principal principal) {
        return new DashboardResponse(
                engine.status(),
                opportunities.findTop100ByOrderByDetectedAtDesc(),
                settings(principal)
        );
    }

    @PostMapping("/scan")
    public ResponseEntity<?> scan() {
        if (engine.status().scanning()) {
            return ResponseEntity.accepted().body(engine.status());
        }
        return ResponseEntity.ok(engine.scan());
    }

    @GetMapping("/opportunities")
    public List<OpportunityEntity> opportunityHistory() {
        return opportunities.findTop100ByOrderByDetectedAtDesc();
    }

    @GetMapping("/symbols")
    public Set<String> symbols() {
        return engine.commonSymbols();
    }

    @GetMapping("/settings")
    public SettingsView settings(Principal principal) {
        RiskSettingsService.Settings settings = settingsService.get(principal.getName());
        return new SettingsView(settings.minQuoteVolume24h(), settings.feePercent(),
                settings.minProfitPercent(), settings.minExpectedProfitKrw(), settings.maxProfitPercent(), settings.orderAmountKrw(),
                settings.maxOrderAmountKrw(), settings.dailyMaxLossKrw(), settings.maxConcurrentPositions(),
                settings.opportunityCooldownSeconds(), settings.profileName(), settings.updatedAt());
    }

    @GetMapping("/risk-presets")
    public List<RiskSettingsService.Preset> riskPresets(Principal principal) {
        return settingsService.presets(principal.getName());
    }

    @PutMapping("/settings")
    public SettingsView updateSettings(Principal principal, @RequestBody SettingsRequest request) {
        settingsService.update(principal.getName(), new RiskSettingsService.Settings(
                request.minQuoteVolume24h(), request.feePercent(), request.minProfitPercent(),
                request.minExpectedProfitKrw(), request.maxProfitPercent(), request.orderAmountKrw(), request.maxOrderAmountKrw(),
                request.dailyMaxLossKrw(), request.maxConcurrentPositions(),
                request.opportunityCooldownSeconds(), request.profileName(), null));
        return settings(principal);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidSettings(IllegalArgumentException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", error.getMessage()));
    }

    public record DashboardResponse(ArbitrageEngine.ScanStatus status,
                                    List<OpportunityEntity> opportunities,
                                    SettingsView settings) {
    }

    public record SettingsView(long minQuoteVolume24h, double feePercent, double minProfitPercent,
                               double minExpectedProfitKrw, double maxProfitPercent,
                               long orderAmountKrw, long maxOrderAmountKrw, long dailyMaxLossKrw,
                               int maxConcurrentPositions, long opportunityCooldownSeconds,
                               String profileName,
                               java.time.Instant updatedAt) {
    }

    public record SettingsRequest(long minQuoteVolume24h, double feePercent,
                                  double minProfitPercent, double minExpectedProfitKrw, double maxProfitPercent,
                                  long orderAmountKrw, long maxOrderAmountKrw,
                                  long dailyMaxLossKrw, int maxConcurrentPositions,
                                  long opportunityCooldownSeconds, String profileName) {
    }
}
