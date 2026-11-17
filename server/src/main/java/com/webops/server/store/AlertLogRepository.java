package com.webops.server.store;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AlertLogRepository extends JpaRepository<AlertLogEntity, Long> {

    List<AlertLogEntity> findBySiteIdOrderByCreatedAtDesc(Long siteId, Pageable pageable);

    @Modifying
    @Query("update AlertLogEntity a set a.siteId = null where a.siteId = :siteId")
    int detachSite(Long siteId);

    /** 검사 기록을 지워도 알림 기록은 사이트 이력으로 남기고, 지운 검사와의 연결만 끊는다 */
    @Modifying
    @Query("update AlertLogEntity a set a.jobId = null where a.jobId = :jobId")
    int detachJob(String jobId);
}
