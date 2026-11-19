package com.webops.server.store;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ScanJobRepository extends JpaRepository<ScanJobEntity, String> {

    /** 최근 검사 목록 (결과 JSON은 읽지 않음) */
    @Query("select j from ScanJobEntity j order by j.createdAt desc")
    List<ScanJobEntity> findRecent(Pageable pageable);

    /** 첫 화면 최근 검사 카드 (페이지 나눔, 전체 건수 포함) */
    Page<ScanJobEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 최근 검사 검색: 주소에 검색어가 들어간 기록 (대소문자 무시) */
    Page<ScanJobEntity> findByUrlContainingIgnoreCaseOrderByCreatedAtDesc(String url, Pageable pageable);

    /** 재검사 비교 기준: 같은 주소 · 같은 검사 범위로 이 검사보다 먼저 끝난 검사 중 가장 최근 */
    Optional<ScanJobEntity> findFirstByUrlAndMaxPagesAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(
            String url, int maxPages, String status, LocalDateTime before);

    /** 사이트의 검사 기록 (오래된 순, 추이 그래프용) */
    List<ScanJobEntity> findBySiteIdOrderByCreatedAtAsc(Long siteId);

    List<ScanJobEntity> findBySiteIdOrderByCreatedAtDesc(Long siteId, Pageable pageable);

    /** 서버가 꺼질 때 진행 중이던 검사 */
    List<ScanJobEntity> findByStatusIn(Collection<String> statuses);

    @Modifying
    @Query("update ScanJobEntity j set j.siteId = null where j.siteId = :siteId")
    int detachSite(Long siteId);

    /** 검사 기록 삭제: 검사 1건 (발견 항목·페이지별 결과를 먼저 지운 뒤 부른다) */
    @Modifying
    @Query("delete from ScanJobEntity j where j.jobId = :jobId")
    int deleteJob(String jobId);

    /** 재검사 비교 후보: 같은 주소 · 같은 범위의 끝난 검사 (최근 순 50개, 점검 종류는 ScanStore에서 거름) */
    java.util.List<ScanJobEntity> findTop50ByUrlAndMaxPagesAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(
            String url, int maxPages, String status, java.time.LocalDateTime before);
}
