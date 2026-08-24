package com.coin.arbitrage.persistence;
import java.util.List; import org.springframework.data.jpa.repository.JpaRepository;
public interface AdminAuditRepository extends JpaRepository<AdminAuditEntity,Long>{ List<AdminAuditEntity> findTop100ByOrderByCreatedAtDesc(); }
