package com.webops.server.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * finding: 발견 항목(사실) (기획서 3장).
 * fingerprint가 같으면 같은 페이지·규칙·위치의 같은 문제 → 재검사 비교(신규/유지/해결)의 열쇠.
 */
@Entity
@Table(name = "finding", indexes = {
        @Index(name = "ix_finding_job", columnList = "job_id"),
        @Index(name = "ix_finding_fp", columnList = "fingerprint")})
public class FindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "finding_id")
    private Long findingId;

    @Column(name = "job_id", length = 64, nullable = false)
    private String jobId;

    @Column(name = "page_url", length = 2000)
    private String pageUrl;

    @Column(name = "category", length = 32)
    private String category;

    @Column(name = "rule_id", length = 100)
    private String ruleId;

    @Column(name = "source", length = 32)
    private String source;

    @Column(name = "severity", length = 16)
    private String severity;

    @Column(name = "selector", length = 1000)
    private String selector;

    @Column(name = "target", length = 2000)
    private String target;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "fingerprint", length = 32)
    private String fingerprint;

    @Column(name = "manual_check_yn", nullable = false)
    private boolean manualCheck;

    @Column(name = "screenshot", length = 200)
    private String screenshot;

    /** 이 문제가 있는 페이지 수. 2 이상이면 머리글·바닥글처럼 여러 페이지에 공통인 문제를 1건으로 합친 것 (page_url은 처음 발견한 페이지) */
    @Column(name = "page_cnt")
    private Integer pageCount;

    protected FindingEntity() {
    }

    public FindingEntity(String jobId, String pageUrl, String category, String ruleId, String source, String severity,
                         String selector, String target, String description, String fingerprint, boolean manualCheck,
                         String screenshot, int pageCount) {
        this.pageCount = pageCount;
        this.jobId = jobId;
        this.pageUrl = cut(pageUrl, 2000);
        this.category = category;
        this.ruleId = cut(ruleId, 100);
        this.source = source;
        this.severity = severity;
        this.selector = cut(selector, 1000);
        this.target = cut(target, 2000);
        this.description = cut(description, 1000);
        this.fingerprint = fingerprint;
        this.manualCheck = manualCheck;
        this.screenshot = cut(screenshot, 200);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public Long getFindingId() { return findingId; }
    public String getJobId() { return jobId; }
    public String getPageUrl() { return pageUrl; }
    public String getCategory() { return category; }
    public String getRuleId() { return ruleId; }
    public String getSource() { return source; }
    public String getSeverity() { return severity; }
    public String getSelector() { return selector; }
    public String getTarget() { return target; }
    public String getDescription() { return description; }
    public String getFingerprint() { return fingerprint; }
    public boolean isManualCheck() { return manualCheck; }
    public String getScreenshot() { return screenshot; }
    public int getPageCount() { return pageCount == null ? 1 : pageCount; }
}
