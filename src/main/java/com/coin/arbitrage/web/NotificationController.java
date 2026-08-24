package com.coin.arbitrage.web;

import com.coin.arbitrage.service.TelegramNotificationService;
import com.coin.arbitrage.service.NotificationSettingsService;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final TelegramNotificationService telegram;
    private final NotificationSettingsService settings;

    public NotificationController(TelegramNotificationService telegram, NotificationSettingsService settings) {
        this.telegram = telegram;
        this.settings = settings;
    }

    @GetMapping("/telegram")
    public Map<String, Object> telegramStatus(Principal principal) {
        NotificationSettingsService.Settings userSettings = settings.get(principal.getName());
        return Map.of(
                "botConfigured", telegram.configured(),
                "configured", telegram.configured(principal.getName()),
                "settings", userSettings
        );
    }

    @PostMapping("/telegram")
    public NotificationSettingsService.Settings updateTelegram(
            Principal principal,
            @org.springframework.web.bind.annotation.RequestBody NotificationSettingsService.UpdateRequest request) {
        return settings.update(principal.getName(), request);
    }

    @PostMapping("/telegram/test")
    public Map<String, String> testTelegram(Principal principal) {
        telegram.sendTestMessage(principal.getName());
        return Map.of("status", "sent");
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalStateException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }
}
