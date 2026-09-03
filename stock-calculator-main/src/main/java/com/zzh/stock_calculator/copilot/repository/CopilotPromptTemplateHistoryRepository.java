package com.zzh.stock_calculator.copilot.repository;

import com.zzh.stock_calculator.copilot.entity.CopilotPromptTemplateHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CopilotPromptTemplateHistoryRepository extends JpaRepository<CopilotPromptTemplateHistory, Long> {

    /** 某标签的历次修改（rev 从新到旧） */
    List<CopilotPromptTemplateHistory> findByTagOrderByRevDesc(String tag);

    /** 某标签已留痕次数（用于计算下一个 rev） */
    long countByTag(String tag);
}
