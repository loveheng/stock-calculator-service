package com.zzh.stock_calculator.orchestration.repository;

import com.zzh.stock_calculator.orchestration.entity.PlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * plan 表仓储（agent-orchestration §6.2/§七）：embedding vector(1024) 列不映射实体，
 * 写入/检索走原生 SQL 显式 CAST（KbChunkRepository 同款 42P18 纪律）。
 */
@Repository
public interface PlanRepository extends JpaRepository<PlanEntity, Long> {

    /** HITL 待审核清单（draft+candidate） */
    java.util.List<PlanEntity> findByStatusInOrderByIdDesc(java.util.Collection<String> statuses);

    /** 向量写入（规划落库后补列）；qv 为 "[a,b,...]" 字面量 */
    @Modifying
    @Query(value = "UPDATE plan SET intent_embedding = CAST(:qv AS vector) WHERE id = :id", nativeQuery = true)
    void updateEmbedding(@Param("id") Long id, @Param("qv") String queryVector);

    /** 复用统计（§6.2 淘汰参考） */
    @Modifying
    @Query(value = "UPDATE plan SET use_count = use_count + 1, last_used_at = CURRENT_TIMESTAMP WHERE id = :id", nativeQuery = true)
    void updateUseStats(@Param("id") Long id);

    /**
     * Filtered Vector Search（§七）：verified 复用池 + intent_domains 数组重叠前置过滤，
     * 防跨域向量噪声「指标计算 ≈ 订阅通知」；needs_review=TRUE 不召回（强制重规划，§八）。
     * domains 为 pg 数组字面量 "{quote,announcement}"。
     */
    @Query(value = "SELECT p.id AS id, (p.intent_embedding <=> CAST(:qv AS vector)) AS distance "
            + "FROM plan p WHERE p.status = 'verified' AND p.needs_review = FALSE "
            + "AND p.intent_embedding IS NOT NULL "
            + "AND p.intent_domains && CAST(:domains AS text[]) "
            + "ORDER BY p.intent_embedding <=> CAST(:qv AS vector) LIMIT :k", nativeQuery = true)
    List<PlanHit> searchVerified(@Param("qv") String queryVector,
                                 @Param("domains") String domains,
                                 @Param("k") int k);

    /** few-shot 注入用：verified 池取 N 条 */
    List<PlanEntity> findTop3ByStatusOrderByLastUsedAtDesc(String status);

    /** registry 变更扫描（§八 惰性回归）：plan_dag 里引用指定 tool 的行 */
    @Query(value = "SELECT * FROM plan WHERE status = 'verified' AND plan_dag::text LIKE %:toolName%", nativeQuery = true)
    List<PlanEntity> findVerifiedReferencingTool(@Param("toolName") String toolName);

    /** 命中行投影（native 查询按别名装配） */
    interface PlanHit {

        Long getId();

        Double getDistance();
    }
}
