package com.zzh.stock_calculator.copilot.repository;

import com.zzh.stock_calculator.copilot.entity.CopilotPromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CopilotPromptTemplateRepository extends JpaRepository<CopilotPromptTemplate, Long> {

    Optional<CopilotPromptTemplate> findByTag(String tag);
}
