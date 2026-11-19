package com.webops.server.scan;

import java.nio.file.Path;
import java.time.LocalDateTime;

import com.webops.server.store.ScanJobEntity;

/**
 * 검사 1건의 상태. 진행 중에는 메모리에서 진행 메시지를 갱신하고, 시작·끝은 DB(scan_job)에 저장한다.
 * 서버를 다시 켜면 DB에서 읽어 같은 모양으로 되살린다 (restore).
 * 검사는 다른 스레드에서 진행되므로 바뀌는 값은 volatile로 둔다.
 */
public class ScanJob {

    public static final String MANUAL = "MANUAL";
    public static final String SCHEDULED = "SCHEDULED";

    private final String id;
    private final String url;
    private final boolean lighthouse;
    /** 검사할 최대 페이지 수 (1이면 입력한 페이지만, 2 이상이면 사이트 전체 검사) */
    private final int maxPages;
    /** 정기 검사 대상(site)에서 나온 검사면 그 번호 */
    private final Long siteId;
    /** MANUAL(직접) / SCHEDULED(정기) */
    private final String trigger;
    private final LocalDateTime createdAt;

    private volatile ScanStatus status = ScanStatus.QUEUED;
    private volatile String progress = "대기 중";
    private volatile ScanReport report;
    private volatile Path reportFile;
    private volatile String error;
    private volatile LocalDateTime finishedAt;
    /** DB에서 되살린 목록용 요약 (결과 JSON을 읽지 않고도 보여 줄 수 있게) */
    private volatile Integer issueCount;
    private volatile Integer qualityScore;
    /** 게시판 글처럼 모양이 같은 페이지를 모두 검사할지 (false = 최신 1개만, 웹사이트 전체 점검에만 해당) */
    private volatile boolean allPosts;
    /** 공공기관 검사: 필수 요소(개인정보처리방침 · 이용약관 · 저작권 표시)도 점검 (false = 일반 검사, 기본) */
    private volatile boolean publicCheck;
    /** 점검 종류 (종합점검 · 기능 · 웹접근성 · 로딩속도 · SEO · 보안 · 모바일환경) */
    private volatile ScanType type = ScanType.ALL;
    /** 모바일환경 점검: 반응형이 아닌 사이트의 모바일 전용 주소 (없으면 null) */
    private volatile String mobileUrl;

    public ScanJob(String id, String url, boolean lighthouse, int maxPages, Long siteId, String trigger) {
        this(id, url, lighthouse, maxPages, siteId, trigger, LocalDateTime.now());
    }

    private ScanJob(String id, String url, boolean lighthouse, int maxPages, Long siteId, String trigger, LocalDateTime createdAt) {
        this.id = id;
        this.url = url;
        this.lighthouse = lighthouse;
        this.maxPages = maxPages;
        this.siteId = siteId;
        this.trigger = trigger;
        this.createdAt = createdAt;
    }

    /** DB 기록 → 화면에서 쓰는 모양. report는 결과 화면을 열 때만 넘긴다 (목록에서는 null) */
    public static ScanJob restore(ScanJobEntity e, ScanReport report) {
        ScanJob job = new ScanJob(e.getJobId(), e.getUrl(), e.isLighthouse(), e.getMaxPages(), e.getSiteId(),
                e.getTriggerType(), e.getCreatedAt());
        job.status = ScanStatus.valueOf(e.getStatus());
        job.progress = job.status.getLabel();
        job.report = report;
        job.reportFile = e.getReportPath() == null ? null : Path.of(e.getReportPath());
        job.error = e.getError();
        job.finishedAt = e.getFinishedAt();
        job.issueCount = e.getIssueCount();
        job.qualityScore = e.getQualityScore();
        job.allPosts = e.isAllPosts();
        job.publicCheck = e.isPublicCheck();
        job.type = ScanType.from(e.getCheckType());
        job.mobileUrl = e.getMobileUrl();
        return job;
    }

    void markRunning() {
        status = ScanStatus.RUNNING;
        progress = "검사 준비 중";
    }

    void updateProgress(String message) {
        progress = message;
    }

    // ---------- 진행 막대 ----------
    // 실행기가 "  % 단계 완료수 전체수"로 알려 준다 (단계: page · discover · scan · lighthouse)
    private volatile String phase;
    private volatile int stepDone;
    private volatile int stepTotal;

    void updateStep(String phase, int done, int total) {
        this.phase = phase;
        this.stepDone = done;
        this.stepTotal = total;
    }

    /**
     * 진행률(0~100).
     * 한 페이지: 단계 순서대로 0~95%. 웹사이트 전체 점검: 페이지 찾기 0~10% → 검사 10~90%(로딩 속도 측정이 있으면) 또는 10~98%.
     */
    public int getPercent() {
        if (isFinished()) return 100;
        if (phase == null) return 2;
        int total = Math.max(1, stepTotal);
        return switch (phase) {
            case "page" -> Math.max(3, Math.round(95f * (stepDone - 1) / total));
            case "discover" -> 5;
            case "scan" -> 10 + Math.round((lighthouse ? 80f : 88f) * Math.min(stepDone, total) / total);
            case "lighthouse" -> 92;
            default -> 2;
        };
    }

    /** 페이지를 찾는 중처럼 끝이 얼마나 남았는지 모르는 동안 (막대를 움직이는 줄무늬로 보여 줌) */
    public boolean isIndeterminate() {
        return !isFinished() && "discover".equals(phase);
    }

    /** 막대 옆에 쓰는 한 줄: "페이지 12 / 37 검사 완료", "검사할 페이지 찾는 중 · 24개 찾음" */
    public String getStepText() {
        if (isFinished()) return status == ScanStatus.DONE ? "검사 완료" : "검사 실패";
        if (phase == null) return "검사 준비 중";
        return switch (phase) {
            case "page" -> "단계 " + stepDone + " / " + stepTotal;
            case "discover" -> "검사할 페이지 찾는 중 · " + stepTotal + "개 찾음";
            case "scan" -> "페이지 " + stepDone + " / " + stepTotal + " 검사 완료";
            case "lighthouse" -> "로딩 속도 · SEO 측정 중 (20~60초)";
            default -> "";
        };
    }

    void complete(ScanReport result, Path file) {
        report = result;
        reportFile = file;
        issueCount = result.summary() == null ? null : result.summary().total();
        qualityScore = QualityScore.scanScore(result);
        progress = "완료";
        finishedAt = LocalDateTime.now();
        status = ScanStatus.DONE;
    }

    void fail(String message) {
        error = message;
        progress = "실패";
        finishedAt = LocalDateTime.now();
        status = ScanStatus.FAILED;
    }

    public boolean isFinished() {
        return status == ScanStatus.DONE || status == ScanStatus.FAILED;
    }

    public String getId() { return id; }
    public String getUrl() { return url; }
    public boolean isLighthouse() { return lighthouse; }
    public int getMaxPages() { return maxPages; }
    public boolean isSiteScan() { return maxPages > 1; }
    public boolean isAllPosts() { return allPosts; }
    void setAllPosts(boolean allPosts) { this.allPosts = allPosts; }
    public boolean isPublicCheck() { return publicCheck; }
    void setPublicCheck(boolean publicCheck) { this.publicCheck = publicCheck; }
    /** 검사 종류 (화면 표시용) */
    public String getAuditLabel() { return publicCheck ? "공공기관 검사" : "일반 검사"; }
    public ScanType getType() { return type; }
    void setType(ScanType type) { this.type = type == null ? ScanType.ALL : type; }
    public String getMobileUrl() { return mobileUrl; }
    void setMobileUrl(String mobileUrl) { this.mobileUrl = mobileUrl; }
    /** 화면 표시: 종합점검이면 검사 범위까지 (예: 종합점검 · 웹사이트 전체 점검), 나머지는 점검 이름 */
    public String getTypeLabel() { return type == ScanType.ALL ? type.getLabel() + " · " + getScope().getLabel() : type.getLabel(); }
    /** 게시판 글 점검 방식 (화면 표시용) */
    public String getPostsLabel() { return allPosts ? "모든 게시물" : "최신 게시물 1개만"; }
    /** 검사 범위 (메인 페이지만 / 입력한 현재 페이지 / 웹사이트 전체 점검) */
    public ScanScope getScope() { return ScanScope.of(url, maxPages); }
    public Long getSiteId() { return siteId; }
    public String getTrigger() { return trigger; }
    public boolean isScheduled() { return SCHEDULED.equals(trigger); }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public ScanStatus getStatus() { return status; }
    public String getProgress() { return progress; }
    public ScanReport getReport() { return report; }
    public Path getReportFile() { return reportFile; }
    public String getError() { return error; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public Integer getIssueCount() { return issueCount; }
    public Integer getQualityScore() { return qualityScore; }
}
