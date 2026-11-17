package com.webops.server.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** scan_page_result: 검사 1회 안의 페이지별 측정값 (기획서 3장) */
@Entity
@Table(name = "scan_page_result", indexes = @Index(name = "ix_page_result_job", columnList = "job_id"))
public class ScanPageResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "result_id")
    private Long resultId;

    @Column(name = "job_id", length = 64, nullable = false)
    private String jobId;

    /** 검사한 순서 (1부터) */
    @Column(name = "page_no", nullable = false)
    private int pageNo;

    @Column(name = "page_url", length = 2000)
    private String pageUrl;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "quality_score")
    private Integer qualityScore;

    @Column(name = "perf_score")
    private Integer perfScore;

    @Column(name = "seo_score")
    private Integer seoScore;

    @Column(name = "issue_cnt")
    private int issueCount;

    protected ScanPageResultEntity() {
    }

    public ScanPageResultEntity(String jobId, int pageNo, String pageUrl, String title, Integer httpStatus,
                                Integer qualityScore, Integer perfScore, Integer seoScore, int issueCount) {
        this.jobId = jobId;
        this.pageNo = pageNo;
        this.pageUrl = pageUrl != null && pageUrl.length() > 2000 ? pageUrl.substring(0, 2000) : pageUrl;
        this.title = title != null && title.length() > 500 ? title.substring(0, 500) : title;
        this.httpStatus = httpStatus;
        this.qualityScore = qualityScore;
        this.perfScore = perfScore;
        this.seoScore = seoScore;
        this.issueCount = issueCount;
    }

    public String getJobId() { return jobId; }
    public int getPageNo() { return pageNo; }
    public String getPageUrl() { return pageUrl; }
    public String getTitle() { return title; }
    public Integer getHttpStatus() { return httpStatus; }
    public Integer getQualityScore() { return qualityScore; }
    public Integer getPerfScore() { return perfScore; }
    public Integer getSeoScore() { return seoScore; }
    public int getIssueCount() { return issueCount; }
}
