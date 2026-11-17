package com.webops.server.store;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteRepository extends JpaRepository<SiteEntity, Long> {

    List<SiteEntity> findAllByOrderByCreatedAtDesc();

    /** 검사할 때가 된 정기 검사 대상 */
    List<SiteEntity> findByEnabledTrueAndNextRunAtLessThanEqual(LocalDateTime now);
}
