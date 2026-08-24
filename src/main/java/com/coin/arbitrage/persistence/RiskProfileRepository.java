package com.coin.arbitrage.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskProfileRepository extends JpaRepository<RiskProfileEntity, String> {
}
