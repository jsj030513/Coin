package com.coin.arbitrage.web;

import com.coin.arbitrage.service.TradingSettingsService;
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
@RequestMapping("/api/trading")
public class TradingSettingsController {
    private final TradingSettingsService service;

    public TradingSettingsController(TradingSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public TradingSettingsService.Status status(Principal principal) {
        return service.status(principal.getName());
    }

    @PutMapping
    public TradingSettingsService.Status update(Principal principal, @RequestBody UpdateRequest request) {
        return service.update(principal.getName(), request.enabled());
    }

    @PostMapping("/emergency-stop")
    public TradingSettingsService.Status emergencyStop(Principal principal) {
        return service.emergencyStop(principal.getName());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> blocked(IllegalStateException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }

    public record UpdateRequest(boolean enabled) { }
}
