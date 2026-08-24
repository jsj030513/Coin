package com.coin.arbitrage.web;

import com.coin.arbitrage.service.TelegramTradeApprovalService;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trade-approvals")
public class TradeApprovalController {
    private final TelegramTradeApprovalService approvals;

    public TradeApprovalController(TelegramTradeApprovalService approvals) {
        this.approvals = approvals;
    }

    @PostMapping("/liquidate-all")
    public TelegramTradeApprovalService.ApprovalView liquidateAll(Principal principal) {
        return approvals.requestLiquidation(principal.getName());
    }

    @PostMapping("/recommended-buy")
    public TelegramTradeApprovalService.ApprovalView recommendedBuy(Principal principal) {
        return approvals.requestRecommendedSeed(principal.getName());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<Map<String, String>> invalid(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
