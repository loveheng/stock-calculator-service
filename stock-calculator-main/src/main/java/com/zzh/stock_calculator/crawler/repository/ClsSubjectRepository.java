package com.zzh.stock_calculator.crawler.repository;
import com.zzh.stock_calculator.crawler.entity.ClsSubject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClsSubjectRepository extends JpaRepository<ClsSubject, Long> {

    /** 题材名含指定子串（短查询实体判定：query 是题材名的子串，如「算力」） */
    List<ClsSubject> findBySubjectNameContaining(String subjectName);
}
