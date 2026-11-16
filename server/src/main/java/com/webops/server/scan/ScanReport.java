package com.webops.server.scan;

import java.util.List;
import java.util.Map;

/**
 * 검사 실행기가 저장한 표준 JSON(schemaVersion 1.0)을 그대로 담는 객체.
 * 필드 이름이 JSON 키와 같아서 자동으로 채워진다.
 */
public record ScanReport(
        String schemaVersion,
        String jobId,
        String startedAt,
        String finishedAt,
        long durationMs,
        List<PageResult> pages,
        Summary summary,
        List<Finding> findings,
        List<String> warnings) {

    public PageResult firstPage() {
        return pages == null || pages.isEmpty() ? null : pages.get(0);
    }

    public record PageResult(
            String requestedUrl,
            String pageUrl,
            Integer httpStatus,
            String title,
            Scores scores,
            Map<String, String> metrics,
            List<Metric> metricDetails,
            Integer linkCount,
            Integer linksChecked,
            Integer imageCount,
            Mobile mobile) {
    }

    /** 로딩 속도 지표 하나 (rating: GOOD / NEEDS_IMPROVEMENT / POOR) */
    public record Metric(String key, String displayValue, Double numericValue, String rating) {
    }

    /** 휴대폰 크기로 열었을 때: 화면 너비와 실제 페이지 너비 */
    public record Mobile(String pageUrl, Integer viewportWidth, Integer scrollWidth) {
    }

    public record Scores(Integer performance, Integer seo) {
    }

    public record Summary(int total, int manualCheck, Map<String, Integer> bySeverity, Map<String, Integer> byCategory) {
    }

    public record Finding(
            String pageUrl,
            String category,
            String ruleId,
            String source,
            String severity,
            String selector,
            String target,
            String evidence,
            String description,
            String helpUrl,
            boolean manualCheck,
            String fingerprint) {
    }
}
