package com.zzh.stock_calculator.kg.repository;

import com.zzh.stock_calculator.kg.entity.KgEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 实体仓库：锚点实体按 uq_kg_entity_anchor 定位，自由实体按 uq_kg_entity_type_name 定位。 */
public interface KgEntityRepository extends JpaRepository<KgEntity, Long> {

    Optional<KgEntity> findByAnchorTypeAndAnchorId(String anchorType, String anchorId);

    Optional<KgEntity> findByEntityTypeAndName(String entityType, String name);
}
