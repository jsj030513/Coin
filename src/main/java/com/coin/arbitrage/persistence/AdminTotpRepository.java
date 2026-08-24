package com.coin.arbitrage.persistence; import java.util.Optional; import org.springframework.data.jpa.repository.JpaRepository;
public interface AdminTotpRepository extends JpaRepository<AdminTotpEntity,Long>{Optional<AdminTotpEntity> findByUserUsername(String username);}
