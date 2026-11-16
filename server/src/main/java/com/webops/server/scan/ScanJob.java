package com.webops.server.scan;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * 검사 1건의 진행 상태. 지금은 메모리에만 보관한다 (서버를 끄면 사라짐 → 2단계에서 DB로 옮김).
 * 검사는 다른 스레드에서 진행되므로 바뀌는 값은 volatile로 둔다.
 */
public class ScanJob {

    private final String id;
    private final String url;
    private final boolean lighthouse;
    private final LocalDateTime createdAt = LocalDateTime.now();

    private volatile ScanStatus status = ScanStatus.QUEUED;
    private volatile String progress = "대기 중";
    private volatile ScanReport report;
    private volatile Path reportFile;
    private volatile String error;
    private volatile LocalDateTime finishedAt;

    public ScanJob(String id, String url, boolean lighthouse) {
        this.id = id;
        this.url = url;
        this.lighthouse = lighthouse;
    }

    void markRunning() {
        status = ScanStatus.RUNNING;
        progress = "검사 준비 중";
    }

    void updateProgress(String message) {
        progress = message;
    }

    void complete(ScanReport result, Path file) {
        report = result;
        reportFile = file;
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
    public LocalDateTime getCreatedAt() { return createdAt; }
    public ScanStatus getStatus() { return status; }
    public String getProgress() { return progress; }
    public ScanReport getReport() { return report; }
    public Path getReportFile() { return reportFile; }
    public String getError() { return error; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
}
