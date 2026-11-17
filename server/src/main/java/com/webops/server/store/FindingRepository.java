package com.webops.server.store;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface FindingRepository extends JpaRepository<FindingEntity, Long> {

    List<FindingEntity> findByJobIdAndManualCheckFalse(String jobId);

    /** 검사 기록 삭제: 그 검사의 발견 항목 전부 */
    @Modifying
    @Query("delete from FindingEntity f where f.jobId = :jobId")
    int deleteByJob(String jobId);
}
