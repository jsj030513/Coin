package com.coin.arbitrage.web;

import com.coin.arbitrage.service.OperationStatusService;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operation-status")
public class OperationStatusController {
    private final OperationStatusService service;

    public OperationStatusController(OperationStatusService service) {
        this.service = service;
    }

    @GetMapping
    public OperationStatusService.Status status(Principal principal) {
        return service.status(principal.getName());
    }
}
