package com.coin.arbitrage.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExternalFeeRepository extends JpaRepository<ExternalFeeEntity, Long> {
    boolean existsByTelegramUpdateId(long telegramUpdateId);

    long countByUserUsername(String username);

    long countByUserUsernameAndFeeDate(String username, LocalDate feeDate);

    @Query("select coalesce(sum(f.amountKrw), 0) from ExternalFeeEntity f where f.user.username = :username")
    BigDecimal sumByUsername(@Param("username") String username);

    @Query("select coalesce(sum(f.amountKrw), 0) from ExternalFeeEntity f where f.user.username = :username and f.feeDate = :feeDate")
    BigDecimal sumByUsernameAndFeeDate(@Param("username") String username,
                                       @Param("feeDate") LocalDate feeDate);
}
