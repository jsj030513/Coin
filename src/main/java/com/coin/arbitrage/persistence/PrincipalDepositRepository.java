package com.coin.arbitrage.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrincipalDepositRepository extends JpaRepository<PrincipalDepositEntity, Long> {
    boolean existsByTelegramUpdateId(long telegramUpdateId);

    @Query("select coalesce(sum(d.amountKrw), 0) from PrincipalDepositEntity d where d.user.username = :username")
    BigDecimal sumByUsername(@Param("username") String username);

    @Query("select coalesce(sum(d.amountKrw), 0) from PrincipalDepositEntity d where d.user.username = :username and d.depositDate = :depositDate")
    BigDecimal sumByUsernameAndDepositDate(@Param("username") String username,
                                           @Param("depositDate") LocalDate depositDate);
}
