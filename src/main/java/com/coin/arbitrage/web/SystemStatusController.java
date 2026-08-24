package com.coin.arbitrage.web;

import com.coin.arbitrage.service.SystemStatusService;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system-status")
public class SystemStatusController {
    private final SystemStatusService service;

    public SystemStatusController(SystemStatusService service) {
        this.service = service;
    }

    @GetMapping
    public SystemStatusService.Status status(Principal principal) {
        return service.status(principal.getName());
    }
}
