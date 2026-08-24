package com.coin.arbitrage.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeConnectionRepository extends JpaRepository<ExchangeConnectionEntity, Long> {
    List<ExchangeConnectionEntity> findByUserUsernameOrderByExchangeAsc(String username);
    Optional<ExchangeConnectionEntity> findByUserUsernameAndExchange(String username, ExchangeConnectionEntity.Exchange exchange);
    Optional<ExchangeConnectionEntity> findFirstByExchangeAndStatus(
            ExchangeConnectionEntity.Exchange exchange, ExchangeConnectionEntity.Status status);
}
