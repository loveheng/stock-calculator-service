package com.zzh.stock_calculator.customstat.repository;

import com.zzh.stock_calculator.customstat.entity.UserCustomStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserCustomStatRepository extends JpaRepository<UserCustomStat, Long> {

    /** GET 全量列表：按 LWW 排序键（客户端 updatedAt ISO 串，原样存储）倒序 */
    List<UserCustomStat> findByUserIdOrderByUpdatedAtClientDesc(String userId);

    boolean existsByUserIdAndDefId(String userId, String defId);

    long countByUserId(String userId);

    /**
     * 单语句幂等 upsert（D17 §2/§4）：(user_id, def_id) 唯一冲突即整行覆盖，无 CAS 无版本仲裁。
     * payload 走 CAST(:payload AS jsonb)——JDBC String 绑定 jsonb 列必须显式转型；
     * created_at 仅首插产生，覆盖路径保留原值（updatedAt 列由服务端时钟维护，仅作运维元数据）。
     *
     * @return 受影响行数（恒 1）
     */
    @Modifying
    @Query(value = """
            INSERT INTO user_custom_stat (user_id, def_id, payload, updated_at_client)
            VALUES (:userId, :defId, CAST(:payload AS jsonb), :updatedAtClient)
            ON CONFLICT (user_id, def_id) DO UPDATE SET
                payload            = EXCLUDED.payload,
                updated_at_client  = EXCLUDED.updated_at_client,
                updated_at         = NOW()
            """, nativeQuery = true)
    int upsertPayload(@Param("userId") String userId,
                      @Param("defId") String defId,
                      @Param("payload") String payload,
                      @Param("updatedAtClient") String updatedAtClient);

    /**
     * 物理删除（D17：删除即物理 DELETE，端上才存在软删墓碑用于删除传播）；
     * 幂等：0 行受影响 = 远端本就不存在，调用方仍返回 200。
     */
    @Modifying
    @Query(value = "DELETE FROM user_custom_stat WHERE user_id = :userId AND def_id = :defId",
            nativeQuery = true)
    int deleteByUserIdAndDefId(@Param("userId") String userId, @Param("defId") String defId);
}
