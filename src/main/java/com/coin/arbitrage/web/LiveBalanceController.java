package com.coin.arbitrage.web;

import com.coin.arbitrage.service.LiveBalanceService;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/live-balances")
public class LiveBalanceController {
    private final LiveBalanceService service;

    public LiveBalanceController(LiveBalanceService service) {
        this.service = service;
    }

    @GetMapping
    public LiveBalanceService.LiveBalanceResponse snapshot(Principal principal) {
        return service.snapshot(principal.getName());
    }
}
