package com.zzh.stock_calculator.repository;

import com.zzh.stock_calculator.dto.ClsSubject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClsSubjectRepository extends JpaRepository<ClsSubject, Long> {
}
