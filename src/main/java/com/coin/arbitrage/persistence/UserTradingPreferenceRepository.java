package com.coin.arbitrage.persistence; import java.util.Optional; import org.springframework.data.jpa.repository.JpaRepository;
public interface UserTradingPreferenceRepository extends JpaRepository<UserTradingPreferenceEntity,Long>{Optional<UserTradingPreferenceEntity> findByUserUsername(String username);}
