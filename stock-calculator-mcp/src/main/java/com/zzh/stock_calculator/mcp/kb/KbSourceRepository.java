package com.zzh.stock_calculator.mcp.kb;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KbSourceRepository extends JpaRepository<KbSourceEntity, Long> {

    Optional<KbSourceEntity> findByName(String name);

    List<KbSourceEntity> findByStatus(String status);
}
