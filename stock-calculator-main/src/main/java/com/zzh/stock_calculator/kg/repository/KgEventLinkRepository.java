package com.zzh.stock_calculator.kg.repository;

import com.zzh.stock_calculator.kg.entity.KgEventLink;
import org.springframework.data.jpa.repository.JpaRepository;

/** 事件-实体关联仓库：事件判重先行，关联随事件幂等，无需额外 exists。 */
public interface KgEventLinkRepository extends JpaRepository<KgEventLink, Long> {
}
