package com.zzh.stock_calculator.kg.repository;

import com.zzh.stock_calculator.kg.entity.KgEventLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** 事件-实体关联仓库：事件判重先行，关联随事件幂等，无需额外 exists。 */
public interface KgEventLinkRepository extends JpaRepository<KgEventLink, Long> {

    /** 批量取事件卡片实体 chips（eventIds 为本页事件集合） */
    List<KgEventLink> findByEventIdIn(Collection<Long> eventIds);

    /** 实体参与事件数（实体摘要卡 eventCount 口径） */
    long countByEntityId(Long entityId);
}
