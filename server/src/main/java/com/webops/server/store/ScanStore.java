package com.webops.server.store;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.webops.server.scan.Comparison;
import com.webops.server.scan.QualityScore;
import com.webops.server.scan.ScanReport;

/**
 * 검사 기록을 DB에 저장하고 읽는다.
 *   scan_job(검사 1회 + 요약 + 결과 JSON) · scan_page_result(페이지별) · finding(발견 항목)
 */
@Service
public class ScanStore {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    private final ScanJobRepository jobs;
    private final ScanPageResultRepository pages;
    private final FindingRepository findings;
    private final AlertLogRepository alerts;

    public ScanStore(ScanJobRepository jobs, ScanPageResultRepository pages, FindingRepository findings,
                     AlertLogRepository alerts) {
        this.jobs = jobs;
        this.pages = pages;
        this.findings = findings;
        this.alerts = alerts;
    }

    /** 지울 수 없을 때 (검사 중이거나 없는 기록). 메시지는 화면에 그대로 보여 준다. */
    public static class CannotDeleteException extends RuntimeException {
        public CannotDeleteException(String message) {
            super(message);
        }
    }

    /**
     * 검사 기록 삭제. 한 트랜잭션 안에서 발견 항목 → 페이지별 결과 → (알림 기록 연결 끊기) → 검사 순으로 지운다.
     * 검사 중인 기록은 지우지 않는다.
     * @return 지운 검사 (결과 파일·캡처를 정리할 수 있게)
     */
    @Transactional
    public ScanJobEntity delete(String jobId) {
        ScanJobEntity job = jobs.findById(jobId)
                .orElseThrow(() -> new CannotDeleteException("검사 기록을 찾을 수 없습니다."));
        if (QUEUED.equals(job.getStatus()) || RUNNING.equals(job.getStatus())) {
            throw new CannotDeleteException("검사가 끝난 뒤에 지울 수 있습니다.");
        }
        findings.deleteByJob(jobId);
        pages.deleteByJob(jobId);
        alerts.detachJob(jobId);
        jobs.deleteJob(jobId);
        return job;
    }

    /** 검사 스레드가 바로 읽을 수 있게, 부른 쪽 트랜잭션과 상관없이 즉시 저장한다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void create(ScanJobEntity job) {
        jobs.save(job);
    }

    @Transactional
    public void markRunning(String jobId) {
        jobs.findById(jobId).ifPresent(j -> j.setStatus(RUNNING));
    }

    /** 검사가 끝나면: 요약 숫자 · 결과 JSON · 페이지별 결과 · 발견 항목을 한 번에 저장 */
    @Transactional
    public void saveDone(String jobId, ScanReport report, String json, String reportPath) {
        ScanJobEntity job = jobs.findById(jobId).orElseThrow();
        ScanReport.Summary s = report.summary();
        Map<String, Integer> sev = s == null || s.bySeverity() == null ? Map.of() : s.bySeverity();
        ScanReport.PageResult first = report.firstPage();

        job.setStatus(DONE);
        job.setFinishedAt(LocalDateTime.now());
        job.setDurationMs(report.durationMs());
        job.setPageCount(report.pages() == null ? 0 : report.pages().size());
        job.setIssueCount(s == null ? 0 : s.total());
        job.setManualCount(s == null ? 0 : s.manualCheck());
        job.setCriticalCount(sev.getOrDefault("CRITICAL", 0));
        job.setHighCount(sev.getOrDefault("HIGH", 0));
        job.setMediumCount(sev.getOrDefault("MEDIUM", 0));
        job.setLowCount(sev.getOrDefault("LOW", 0));
        job.setQualityScore(QualityScore.scanScore(report));
        job.setPerfScore(first != null && first.scores() != null ? first.scores().performance() : null);
        job.setSeoScore(first != null && first.scores() != null ? first.scores().seo() : null);
        job.setSiteDown(first == null || !QualityScore.opened(first));
        job.setReportPath(reportPath);
        job.setReportJson(json);

        List<ScanReport.Finding> all = report.findings() == null ? List.of() : report.findings();
        if (report.pages() != null) {
            int no = 0;
            for (ScanReport.PageResult p : report.pages()) {
                no++;
                int count = (int) all.stream().filter(f -> !f.manualCheck() && f.onPage(p.pageUrl())).count();
                pages.save(new ScanPageResultEntity(jobId, no, p.pageUrl(), p.title(), p.httpStatus(),
                        QualityScore.pageScore(report, p),
                        p.scores() == null ? null : p.scores().performance(),
                        p.scores() == null ? null : p.scores().seo(), count));
            }
        }
        List<FindingEntity> rows = new ArrayList<>(all.size());
        for (ScanReport.Finding f : all) {
            rows.add(new FindingEntity(jobId, f.pageUrl(), f.category(), f.ruleId(), f.source(), f.severity(),
                    f.selector(), f.target(), f.description(), f.fingerprint(), f.manualCheck(), f.screenshot(),
                    f.pageList().size()));
        }
        findings.saveAll(rows);
    }

    @Transactional
    public void saveFailed(String jobId, String error) {
        jobs.findById(jobId).ifPresent(j -> {
            j.setStatus(FAILED);
            j.setError(error != null && error.length() > 2000 ? error.substring(0, 2000) : error);
            j.setFinishedAt(LocalDateTime.now());
        });
    }

    @Transactional(readOnly = true)
    public Optional<ScanJobEntity> find(String jobId) {
        return jobs.findById(jobId);
    }

    @Transactional(readOnly = true)
    public List<ScanJobEntity> recent(int limit) {
        return jobs.findRecent(PageRequest.of(0, limit));
    }

    /** 최근 검사 한 쪽 (page는 0부터) */
    @Transactional(readOnly = true)
    public Page<ScanJobEntity> recentPage(int page, int size, String query) {
        PageRequest pr = PageRequest.of(Math.max(0, page), size);
        if (query == null || query.isBlank()) return jobs.findAllByOrderByCreatedAtDesc(pr);
        return jobs.findByUrlContainingIgnoreCaseOrderByCreatedAtDesc(query.strip(), pr);
    }

    /** 검사 기록 전체 건수 */
    @Transactional(readOnly = true)
    public long count() {
        return jobs.count();
    }

    /** 서버를 다시 켰을 때: 꺼지기 전 진행 중이던 검사는 실패로 정리 */
    @Transactional
    public int failInterrupted() {
        List<ScanJobEntity> stale = jobs.findByStatusIn(List.of(QUEUED, RUNNING));
        for (ScanJobEntity j : stale) {
            j.setStatus(FAILED);
            j.setError("서버가 꺼져 검사가 중단되었습니다. 다시 검사해 주세요.");
            j.setFinishedAt(LocalDateTime.now());
        }
        return stale.size();
    }

    /** 이 검사의 비교 기준이 되는 지난 검사 (같은 주소 · 같은 범위, 끝난 것 중 가장 최근) */
    @Transactional(readOnly = true)
    public Optional<ScanJobEntity> previousOf(ScanJobEntity job) {
        String type = job.getCheckType() == null ? "ALL" : job.getCheckType();
        return jobs.findTop50ByUrlAndMaxPagesAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(
                        job.getUrl(), job.getMaxPages(), DONE, job.getCreatedAt()).stream()
                .filter(j -> type.equals(j.getCheckType() == null ? "ALL" : j.getCheckType()))
                .findFirst();
    }

    /** 재검사 비교. 지난 검사가 없으면 비어 있음 */
    @Transactional(readOnly = true)
    public Optional<Comparison> compare(String jobId) {
        Optional<ScanJobEntity> current = jobs.findById(jobId).filter(j -> DONE.equals(j.getStatus()));
        if (current.isEmpty()) return Optional.empty();
        Optional<ScanJobEntity> previous = previousOf(current.get());
        if (previous.isEmpty()) return Optional.empty();

        List<FindingEntity> now = findings.findByJobIdAndManualCheckFalse(jobId);
        List<FindingEntity> before = findings.findByJobIdAndManualCheckFalse(previous.get().getJobId());
        Set<String> nowFp = fingerprints(now);
        Set<String> beforeFp = fingerprints(before);

        Set<String> added = new HashSet<>(nowFp);
        added.removeAll(beforeFp);
        int kept = (int) nowFp.stream().filter(beforeFp::contains).count();
        List<ScanReport.Finding> resolved = before.stream()
                .filter(f -> !nowFp.contains(f.getFingerprint()))
                .map(ScanStore::toFinding)
                .toList();
        return Optional.of(new Comparison(previous.get().getJobId(), previous.get().getCreatedAt(),
                previous.get().getQualityScore(), current.get().getQualityScore(), added, kept, resolved));
    }

    /** 사이트의 검사 기록 (오래된 순) */
    @Transactional(readOnly = true)
    public List<ScanJobEntity> historyOf(Long siteId) {
        return jobs.findBySiteIdOrderByCreatedAtAsc(siteId);
    }

    /** 사이트의 가장 최근 검사 (끝난 것 · 진행 중 모두, 목록 표시용) */
    @Transactional(readOnly = true)
    public Optional<ScanJobEntity> latestOf(Long siteId) {
        return jobs.findBySiteIdOrderByCreatedAtDesc(siteId, PageRequest.of(0, 1)).stream().findFirst();
    }

    /** 발견 항목 (직접 확인 필요 제외, 알림 판단용) */
    @Transactional(readOnly = true)
    public List<FindingEntity> findingsOf(String jobId) {
        return findings.findByJobIdAndManualCheckFalse(jobId);
    }

    private static Set<String> fingerprints(List<FindingEntity> list) {
        Set<String> set = new HashSet<>();
        for (FindingEntity f : list) {
            if (f.getFingerprint() != null) set.add(f.getFingerprint());
        }
        return set;
    }

    static ScanReport.Finding toFinding(FindingEntity f) {
        return new ScanReport.Finding(f.getPageUrl(), f.getCategory(), f.getRuleId(), f.getSource(), f.getSeverity(),
                f.getSelector(), f.getTarget(), null, f.getDescription(), null, f.isManualCheck(), f.getScreenshot(),
                f.getFingerprint(), null, null);
    }
}
