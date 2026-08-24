package com.coin.arbitrage.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OpportunityRepository extends JpaRepository<OpportunityEntity, Long> {
    List<OpportunityEntity> findTop100ByOrderByDetectedAtDesc();
    List<OpportunityEntity> findTop1000ByOrderByDetectedAtDesc();

    @Query("""
            select o.symbol as symbol, count(o) as occurrenceCount,
                   sum(o.expectedProfitKrw) as totalExpectedProfitKrw,
                   avg(o.netProfitPercent) as averageProfitPercent,
                   max(o.detectedAt) as lastDetectedAt
            from OpportunityEntity o
            where o.detectedAt >= :since
              and o.netProfitPercent >= :minProfit
              and o.netProfitPercent <= :maxProfit
            group by o.symbol
            order by count(o) desc, sum(o.expectedProfitKrw) desc
            """)
    List<OpportunityPerformance> summarizeSince(@Param("since") Instant since,
                                                @Param("minProfit") double minProfit,
                                                @Param("maxProfit") double maxProfit);

    interface OpportunityPerformance {
        String getSymbol();
        long getOccurrenceCount();
        double getTotalExpectedProfitKrw();
        double getAverageProfitPercent();
        Instant getLastDetectedAt();
    }
}
