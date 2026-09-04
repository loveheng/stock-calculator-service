package com.zzh.stock_calculator.sync.repository;

import com.zzh.stock_calculator.sync.entity.UserSyncHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserSyncHistoryRepository extends JpaRepository<UserSyncHistory, Long> {

    /**
     * E7：唯一冲突静默吸收（服务端整库回滚后重推同版本场景），返回 0 不影响主流程。
     * 必须 native：冲突吸收是 PG 方言行为（H2 语义有差异，禁用 H2 跑相关用例）。
     */
    @Modifying
    @Query(value = """
            INSERT INTO user_sync_history (user_id, version, encrypted_payload, payload_bytes)
            VALUES (:userId, :version, :payload, :bytes)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIgnore(@Param("userId") String userId,
                     @Param("version") Long version,
                     @Param("payload") String payload,
                     @Param("bytes") Integer bytes);

    /** D8 裁剪：DELETE version < newVersion - 5，保留 {N-5..N-1} 恰 5 份
     *  （前端 spec 的 “DELETE <= newVersion - 5” 是 off-by-one，只能留 4 份，见 implementation §12 #9） */
    void deleteByUserIdAndVersionLessThan(String userId, Long version);
}
