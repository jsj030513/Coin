package com.coin.arbitrage.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortfolioPlanSettingsRepository extends JpaRepository<PortfolioPlanSettingsEntity, Long> {
    Optional<PortfolioPlanSettingsEntity> findByUserUsername(String username);
}
