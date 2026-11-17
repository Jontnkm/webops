package com.webops.server.store;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * scan_job: 검사 1회 실행 (기획서 3장).
 * 요약 숫자는 목록·추이 그래프용으로 컬럼에 따로 두고, 결과 화면은 report_json(실행기 표준 JSON 원본)으로 그린다.
 */
@Entity
@Table(name = "scan_job", indexes = {
        @Index(name = "ix_scan_job_url", columnList = "url, created_at"),
        @Index(name = "ix_scan_job_site", columnList = "site_id, created_at")})
public class ScanJobEntity {

    @Id
    @Column(name = "job_id", length = 64)
    private String jobId;

    /** 정기 검사 대상(site)에서 나온 검사면 그 번호, 직접 검사면 null */
    @Column(name = "site_id")
    private Long siteId;

    @Column(name = "url", length = 2000, nullable = false)
    private String url;

    /** QUEUED / RUNNING / DONE / FAILED */
    @Column(name = "status", length = 16, nullable = false)
    private String status;

    /** MANUAL(직접) / SCHEDULED(정기) */
    @Column(name = "trigger_type", length = 16, nullable = false)
    private String triggerType;

    @Column(name = "max_pages", nullable = false)
    private int maxPages;

    @Column(name = "lighthouse_yn", nullable = false)
    private boolean lighthouse;

    /** 게시판 글처럼 모양이 같은 페이지: true = 모두 검사, false/null = 최신 1개만 (웹사이트 전체 점검에만 해당) */
    @Column(name = "all_posts_yn")
    private Boolean allPosts;

    @Column(name = "error_msg", length = 2000)
    private String error;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    // ---- 요약 (검사가 끝나면 채움) ----
    @Column(name = "page_cnt")
    private Integer pageCount;
    @Column(name = "issue_cnt")
    private Integer issueCount;
    @Column(name = "manual_cnt")
    private Integer manualCount;
    @Column(name = "critical_cnt")
    private Integer criticalCount;
    @Column(name = "high_cnt")
    private Integer highCount;
    @Column(name = "medium_cnt")
    private Integer mediumCount;
    @Column(name = "low_cnt")
    private Integer lowCount;
    /** WebOps 품질 점수 (페이지별 점수의 평균, 열리지 않은 페이지는 0점) */
    @Column(name = "quality_score")
    private Integer qualityScore;
    /** Lighthouse 로딩 속도 · 검색 노출 점수 (첫 페이지, 측정했을 때만) */
    @Column(name = "perf_score")
    private Integer perfScore;
    @Column(name = "seo_score")
    private Integer seoScore;
    /** 첫 페이지가 열리지 않았는지 (사이트 접속 불가) */
    @Column(name = "site_down_yn")
    private Boolean siteDown;

    @Column(name = "report_path", length = 1000)
    private String reportPath;

    @Lob
    @Column(name = "report_json")
    private String reportJson;

    protected ScanJobEntity() {
    }

    public ScanJobEntity(String jobId, Long siteId, String url, String triggerType, int maxPages, boolean lighthouse,
                         LocalDateTime createdAt) {
        this.jobId = jobId;
        this.siteId = siteId;
        this.url = url;
        this.status = "QUEUED";
        this.triggerType = triggerType;
        this.maxPages = maxPages;
        this.lighthouse = lighthouse;
        this.createdAt = createdAt;
    }

    public String getJobId() { return jobId; }
    public Long getSiteId() { return siteId; }
    public void setSiteId(Long siteId) { this.siteId = siteId; }
    public String getUrl() { return url; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTriggerType() { return triggerType; }
    public int getMaxPages() { return maxPages; }
    public boolean isLighthouse() { return lighthouse; }
    public boolean isAllPosts() { return Boolean.TRUE.equals(allPosts); }
    public void setAllPosts(boolean allPosts) { this.allPosts = allPosts; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
    public Integer getIssueCount() { return issueCount; }
    public void setIssueCount(Integer issueCount) { this.issueCount = issueCount; }
    public Integer getManualCount() { return manualCount; }
    public void setManualCount(Integer manualCount) { this.manualCount = manualCount; }
    public Integer getCriticalCount() { return criticalCount; }
    public void setCriticalCount(Integer criticalCount) { this.criticalCount = criticalCount; }
    public Integer getHighCount() { return highCount; }
    public void setHighCount(Integer highCount) { this.highCount = highCount; }
    public Integer getMediumCount() { return mediumCount; }
    public void setMediumCount(Integer mediumCount) { this.mediumCount = mediumCount; }
    public Integer getLowCount() { return lowCount; }
    public void setLowCount(Integer lowCount) { this.lowCount = lowCount; }
    public Integer getQualityScore() { return qualityScore; }
    public void setQualityScore(Integer qualityScore) { this.qualityScore = qualityScore; }
    public Integer getPerfScore() { return perfScore; }
    public void setPerfScore(Integer perfScore) { this.perfScore = perfScore; }
    public Integer getSeoScore() { return seoScore; }
    public void setSeoScore(Integer seoScore) { this.seoScore = seoScore; }
    public boolean isSiteDown() { return Boolean.TRUE.equals(siteDown); }
    public void setSiteDown(Boolean siteDown) { this.siteDown = siteDown; }
    public String getReportPath() { return reportPath; }
    public void setReportPath(String reportPath) { this.reportPath = reportPath; }
    public String getReportJson() { return reportJson; }
    public void setReportJson(String reportJson) { this.reportJson = reportJson; }
}
