package com.coin.arbitrage.persistence;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TradeCycleRepository extends JpaRepository<TradeCycleEntity, String> {
    @EntityGraph(attributePaths = "user")
    List<TradeCycleEntity> findTop100ByStatusOrderByCreatedAtAsc(TradeCycleEntity.Status status);
    @EntityGraph(attributePaths = "user")
    List<TradeCycleEntity> findTop100ByStatusInOrderByCreatedAtAsc(Collection<TradeCycleEntity.Status> statuses);
    long countByUserUsernameAndStatusIn(String username, Collection<TradeCycleEntity.Status> statuses);
    long countByUserUsernameAndStatusInAndCreatedAtAfter(String username, Collection<TradeCycleEntity.Status> statuses, Instant since);
    long countByUserUsernameAndStatus(String username, TradeCycleEntity.Status status);
    long countByUserUsernameAndStatusAndCreatedAtAfter(String username, TradeCycleEntity.Status status, Instant since);
    List<TradeCycleEntity> findByUserUsernameAndCreatedAtAfter(String username, Instant since);
    List<TradeCycleEntity> findTop100ByUserUsernameOrderByCreatedAtDesc(String username);
    TradeCycleEntity findTopByUserUsernameOrderByCreatedAtDesc(String username);
    TradeCycleEntity findTopByUserUsernameAndStatusInOrderByCreatedAtAsc(String username, Collection<TradeCycleEntity.Status> statuses);
    @Query("select coalesce(sum(c.realizedProfitKrw), 0) from TradeCycleEntity c where c.user.username = :username")
    BigDecimal sumRealizedProfit(@Param("username") String username);
    @Query("select coalesce(sum(c.realizedProfitKrw), 0) from TradeCycleEntity c where c.user.username = :username and c.createdAt >= :since")
    BigDecimal sumRealizedProfitSince(@Param("username") String username, @Param("since") Instant since);
    @Query("""
            select c.symbol from TradeCycleEntity c
            where c.user.username = :username
              and c.status = com.coin.arbitrage.persistence.TradeCycleEntity.Status.COMPLETED
              and c.createdAt >= :since
            group by c.symbol
            having sum(c.realizedProfitKrw) > 0
            order by sum(c.realizedProfitKrw) desc, count(c) desc
            """)
    List<String> findProfitableSymbolsSince(@Param("username") String username, @Param("since") Instant since);
}
