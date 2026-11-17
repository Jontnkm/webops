package com.webops.server.store;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ScanPageResultRepository extends JpaRepository<ScanPageResultEntity, Long> {

    List<ScanPageResultEntity> findByJobIdOrderByPageNo(String jobId);

    /** 검사 기록 삭제: 그 검사의 페이지별 결과 전부 */
    @Modifying
    @Query("delete from ScanPageResultEntity p where p.jobId = :jobId")
    int deleteByJob(String jobId);
}
