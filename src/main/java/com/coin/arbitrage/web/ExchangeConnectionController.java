package com.coin.arbitrage.web;

import com.coin.arbitrage.service.ExchangeConnectionService;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exchange-connections")
public class ExchangeConnectionController {
    private final ExchangeConnectionService service;
    private final com.coin.arbitrage.service.ExchangeFeeAnalysisService fees;

    public ExchangeConnectionController(ExchangeConnectionService service,com.coin.arbitrage.service.ExchangeFeeAnalysisService fees) { this.service = service; this.fees=fees; }

    @GetMapping
    public List<ExchangeConnectionService.ConnectionView> list(Principal principal) {
        return service.list(principal.getName());
    }

    @PostMapping("/fees/refresh")
    public List<ExchangeConnectionService.ConnectionView> refreshFees(Principal principal) {
        fees.refreshUser(principal.getName()); return service.list(principal.getName());
    }

    @PostMapping("/{exchange}")
    public ExchangeConnectionService.ConnectionView save(@PathVariable String exchange,
                                                         @RequestBody CredentialsRequest request,
                                                         Principal principal) {
        return service.saveAndVerify(principal.getName(), exchange, request.accessKey(), request.secretKey());
    }

    @PostMapping("/{exchange}/verify")
    public ExchangeConnectionService.ConnectionView verify(@PathVariable String exchange, Principal principal) {
        return service.verify(principal.getName(), exchange);
    }

    @DeleteMapping("/{exchange}")
    public ResponseEntity<Void> delete(@PathVariable String exchange, Principal principal) {
        service.delete(principal.getName(), exchange);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }

    public record CredentialsRequest(String accessKey, String secretKey) { }
}
