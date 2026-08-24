package com.coin.arbitrage.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortfolioOnboardingRepository extends JpaRepository<PortfolioOnboardingEntity,Long> {
    List<PortfolioOnboardingEntity> findByStatus(PortfolioOnboardingEntity.Status status);
    Optional<PortfolioOnboardingEntity> findByDecisionToken(String token);
    Optional<PortfolioOnboardingEntity> findByUserUsername(String username);
}
