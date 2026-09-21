package com.zzh.stock_calculator.mcp.kb;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KbChunkRepository extends JpaRepository<KbChunkEntity, Long> {

    long deleteByBookId(Long bookId);

    long countByBookId(Long bookId);

    List<KbChunkEntity> findByBookIdOrderByChunkIndexAsc(Long bookId);

    /** 已入库 hash 集（RSS 增量去重：feed 是持续流，条目 hash 稳定，按 hash 跳过有意义） */
    @Query(value = "SELECT content_hash FROM kb_chunk WHERE book_id = :bookId AND content_hash IS NOT NULL",
            nativeQuery = true)
    List<String> findHashesByBookId(@Param("bookId") Long bookId);

    /** 原生向量写入（实体不映射 vector 列）；qv 为 "[a,b,...]" 字面量，显式 CAST 定型（42P18 纪律） */
    @Modifying
    @Query(value = "UPDATE kb_chunk SET embedding = CAST(:qv AS vector) WHERE id = :id", nativeQuery = true)
    void updateEmbedding(@Param("id") Long id, @Param("qv") String queryVector);

    /** cosine top-K（HNSW 索引）；qv 同上显式 CAST */
    @Query(value = "SELECT c.id AS id, (c.embedding <=> CAST(:qv AS vector)) AS distance "
            + "FROM kb_chunk c WHERE c.embedding IS NOT NULL "
            + "ORDER BY c.embedding <=> CAST(:qv AS vector) LIMIT :k", nativeQuery = true)
    List<KbChunkHit> searchTopK(@Param("qv") String queryVector, @Param("k") int k);

    /** 术语精确/包含兜底路（向量召回对精确术语名易飘，design.md §8.3） */
    @Query(value = "SELECT c.id AS id, 0.0 AS distance FROM kb_chunk c "
            + "WHERE c.content ILIKE :kw LIMIT :k", nativeQuery = true)
    List<KbChunkHit> searchKeyword(@Param("kw") String keyword, @Param("k") int k);

    /** 命中行投影（native 查询按别名装配） */
    interface KbChunkHit {

        Long getId();

        Double getDistance();
    }
}
