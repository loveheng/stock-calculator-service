package com.zzh.stock_calculator.crawler.repository;
import com.zzh.stock_calculator.crawler.entity.ClsArticleSubject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ClsArticleSubjectRepository extends JpaRepository<ClsArticleSubject, Long> {

    /** 批量取文章-题材关联（E2E 幂等校验用，风格对齐 ClsArticleStockRepository） */
    List<ClsArticleSubject> findByArticleIdIn(Collection<Long> articleIds);
}
