package com.coin.arbitrage.service;

import com.coin.arbitrage.domain.OrderStatus;
import com.coin.arbitrage.persistence.LiveOrderEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class LiveOrderStatusSyncService {
    private static final Logger log = LoggerFactory.getLogger(LiveOrderStatusSyncService.class);
    private final LiveOrderHistoryService history;
    private final LiveExchangeOrderService exchangeOrders;

    public LiveOrderStatusSyncService(LiveOrderHistoryService history,
                                      LiveExchangeOrderService exchangeOrders) {
        this.history = history;
        this.exchangeOrders = exchangeOrders;
    }

    @Scheduled(fixedDelayString = "${live-trading.order-status-sync-ms:10000}")
    public void sync() {
        for (LiveOrderEntity order : history.pendingForSync()) {
            try {
                OrderStatus status = exchangeOrders.getOrderStatus(order.getUser().getUsername(),
                        order.getExchange(), order.getOrderId());
                history.updateExecution(order.getId(), status.status(), status.executedQuantity(),
                        status.executedPrice());
                log.info("Live order status synchronized | exchange={} symbol={} status={}",
                        order.getExchange(), order.getSymbol(), status.status());
                Thread.sleep(120);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                log.warn("Live order status synchronization failed | exchange={} symbol={} reason={}",
                        order.getExchange(), order.getSymbol(), error.getMessage());
            }
        }
    }
}
