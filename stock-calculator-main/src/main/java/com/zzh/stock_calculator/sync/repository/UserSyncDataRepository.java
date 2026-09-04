package com.zzh.stock_calculator.sync.repository;

import com.zzh.stock_calculator.sync.entity.UserSyncData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserSyncDataRepository extends JpaRepository<UserSyncData, String> {

    /**
     * 乐观 CAS upsert（design §5.1，防并发竞态的关键单语句）。
     * 首传（baseVersion=0）：行不存在 → INSERT 直接成功；并发首传落入 ON CONFLICT
     * 且 version(1) != 0 → 0 行受影响。
     * 覆盖：仅当现库 version == baseVersion 才写，version 原子 +1。
     * 云端为空但 baseVersion>0：INSERT 路径不校验 baseVersion，按首传成功（v1）处理，
     * 由客户端回退检测（D14）自然收敛。
     *
     * @return 受影响行数：1 = 写入成功；0 = 冲突
     */
    @Modifying
    @Query(value = """
            INSERT INTO user_sync_data (user_id, encrypted_payload, version, payload_hash, payload_bytes)
            VALUES (:userId, :payload, 1, :hash, :bytes)
            ON CONFLICT (user_id) DO UPDATE SET
                encrypted_payload = EXCLUDED.encrypted_payload,
                version           = user_sync_data.version + 1,
                payload_hash      = EXCLUDED.payload_hash,
                payload_bytes     = EXCLUDED.payload_bytes,
                updated_at        = NOW()
            WHERE user_sync_data.version = :baseVersion
            """, nativeQuery = true)
    int casUpsert(@Param("userId") String userId,
                  @Param("payload") String payload,
                  @Param("hash") String hash,
                  @Param("bytes") Integer bytes,
                  @Param("baseVersion") Long baseVersion);

    /**
     * CAS 成功后回读实际版本（E2）。
     * 必须 native：findById 会命中一级缓存返回 CAS 前旧实体（覆盖路径 current 已在
     * 事务开头加载）；标量原生查询不查/不填持久化上下文，同事务 READ COMMITTED
     * 可见自身未提交写入，回读值即本次写入值。
     */
    @Query(value = "SELECT version FROM user_sync_data WHERE user_id = :userId", nativeQuery = true)
    Long selectVersion(@Param("userId") String userId);
}
