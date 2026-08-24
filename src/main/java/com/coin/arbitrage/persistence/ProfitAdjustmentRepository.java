package com.coin.arbitrage.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProfitAdjustmentRepository extends JpaRepository<ProfitAdjustmentEntity, Long> {
    @Query("select coalesce(sum(a.amountKrw), 0) from ProfitAdjustmentEntity a where a.user.username = :username")
    BigDecimal sumByUsername(@Param("username") String username);

    @Query("""
            select coalesce(sum(a.amountKrw), 0)
            from ProfitAdjustmentEntity a
            where a.user.username = :username and a.createdAt >= :since
            """)
    BigDecimal sumByUsernameSince(@Param("username") String username, @Param("since") Instant since);
}
