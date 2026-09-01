package com.zzh.stock_calculator.crawler.service;
import com.zzh.stock_calculator.crawler.entity.ClsSubject;
import com.zzh.stock_calculator.crawler.repository.ClsSubjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClsSubjectService {

    private final ClsSubjectRepository clsSubjectRepository;

    /**
     * 不存在则插入，已存在则跳过
     */
    @Transactional
    public void upsertIfNotExists(ClsSubject subject) {
        if (clsSubjectRepository.existsById(subject.getSubjectId())) {
            return;
        }
        clsSubjectRepository.save(subject);
        log.debug("inserted new subject, subjectId={}, name={}",
                subject.getSubjectId(), subject.getSubjectName());
    }
}
