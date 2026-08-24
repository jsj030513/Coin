package com.coin.arbitrage.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.EntityGraph;

public interface LiveOrderRepository extends JpaRepository<LiveOrderEntity, Long>, JpaSpecificationExecutor<LiveOrderEntity> {
    Optional<LiveOrderEntity> findByOrderId(String orderId);
    Optional<LiveOrderEntity> findTopByUserUsernameAndStatusOrderByCreatedAtDesc(String username, String status);
    List<LiveOrderEntity> findTop100ByUserUsernameOrderByCreatedAtDesc(String username);
    List<LiveOrderEntity> findByUserUsernameAndExchangeAndSymbolOrderByCreatedAtAsc(
            String username, String exchange, String symbol);
    boolean existsByUserUsernameAndExchangeAndSymbolAndSourceAndCreatedAtAfter(
            String username, String exchange, String symbol, String source, Instant createdAt);
    boolean existsByUserUsernameAndSourceAndCreatedAtAfter(
            String username, String source, Instant createdAt);
    boolean existsByUserUsernameAndExchangeAndSymbolAndSource(
            String username, String exchange, String symbol, String source);
    long countByUserUsernameAndStatusAndCreatedAtAfter(String username, String status, Instant createdAt);
    @EntityGraph(attributePaths = "user")
    List<LiveOrderEntity> findTop200ByStatusInAndCreatedAtAfterOrderByCreatedAtAsc(
            List<String> statuses, Instant createdAt);
}
