package com.coin.arbitrage.web;

import com.coin.arbitrage.persistence.LiveOrderEntity;
import com.coin.arbitrage.service.LiveOrderHistoryService;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/live-order-history")
public class LiveOrderHistoryController {
    private final LiveOrderHistoryService service;

    public LiveOrderHistoryController(LiveOrderHistoryService service) {
        this.service = service;
    }

    @GetMapping
    public PageView recent(Principal principal,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "20") int size,
                           @RequestParam(required = false) String exchange,
                           @RequestParam(required = false) String side,
                           @RequestParam(required = false) String symbol,
                           @RequestParam(required = false) String source,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        var result = service.search(principal.getName(), exchange, side, symbol, source, from, to, page, size);
        return new PageView(result.getContent().stream().map(LiveOrderRow::from).toList(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages(), result.isFirst(), result.isLast());
    }

    public record PageView(List<LiveOrderRow> content, int page, int size, long totalElements,
                           int totalPages, boolean first, boolean last) { }

    public record LiveOrderRow(String exchange, String symbol, String side, String orderId,
                               String requestedKrw, String quantity, String executedPrice,
                               String status, String source, Instant createdAt) {
        static LiveOrderRow from(LiveOrderEntity value) {
            return new LiveOrderRow(value.getExchange(), value.getSymbol(), value.getSide(), value.getOrderId(),
                    value.getRequestedKrw().toPlainString(), value.getQuantity().toPlainString(),
                    value.getExecutedPrice().toPlainString(), value.getStatus(), value.getSource(), value.getCreatedAt());
        }
    }
}
