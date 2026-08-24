package com.coin.arbitrage.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradingSettingsRepository extends JpaRepository<TradingSettingsEntity, Long> {
    Optional<TradingSettingsEntity> findByUserUsername(String username);
}
