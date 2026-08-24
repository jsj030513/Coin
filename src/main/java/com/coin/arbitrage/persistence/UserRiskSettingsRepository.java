package com.coin.arbitrage.persistence;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UserRiskSettingsRepository extends JpaRepository<UserRiskSettingsEntity, Long> {
    Optional<UserRiskSettingsEntity> findByUserUsername(String username);
}
