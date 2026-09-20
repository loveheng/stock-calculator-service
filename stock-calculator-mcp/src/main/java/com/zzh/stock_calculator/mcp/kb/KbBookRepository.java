package com.zzh.stock_calculator.mcp.kb;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface KbBookRepository extends JpaRepository<KbBookEntity, Long> {

    Optional<KbBookEntity> findByTitle(String title);
}
