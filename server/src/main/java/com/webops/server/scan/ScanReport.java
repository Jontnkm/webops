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
        Scope scope,
        List<PageResult> pages,
        Summary summary,
        List<Finding> findings,
        List<String> warnings) {

    public PageResult firstPage() {
        return pages == null || pages.isEmpty() ? null : pages.get(0);
    }

    /** 여러 페이지를 검사했는지 (사이트 전체 검사) */
    public boolean multiPage() {
        return pages != null && pages.size() > 1;
    }

    /**
     * 검사 범위: maxPages(요청한 최대 페이지 수), pageCount(실제로 검사한 수),
     * linksChecked(확인한 서로 다른 링크 수), scrolled(끝까지 스크롤했는지)
     */
    /** check: 점검 종류(all · function · a11y · perf · seo · security · mobile, 예전 결과는 null = 종합) · mobileUrl: 모바일 전용 주소 */
    public record Scope(Integer maxPages, Integer pageCount, Integer linksChecked, Boolean scrolled, String check, String mobileUrl) {
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
            Scroll scroll,
            Mobile mobile,
            Essentials essentials,
            Cert cert,
            Summary summary) {
    }

    /** 보안 인증서 (보안 점검의 첫 페이지): 만료일 · 남은 날 · 발급 기관 · 신뢰할 수 있는지 */
    public record Cert(String validTo, Integer daysLeft, String issuer, Boolean trusted) {
    }

    /** 공공 웹사이트 필수 요소에서 찾은 문구 (못 찾으면 null). 확인하지 않은 페이지·예전 결과는 essentials 자체가 null */
    public record Essentials(String privacy, String terms, String copyright) {
    }

    /** 끝까지 스크롤한 결과: 내린 횟수, 페이지 높이(px), 끝에 닿았는지, 스크롤하며 추가로 불러온 파일 수 */
    public record Scroll(Integer steps, Integer pageHeight, Boolean reachedEnd, Integer extraRequests) {
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
            String screenshot,
            String fingerprint,
            List<String> pages,
            Integer occurrences) {

        /** 같은 자리에서 실제로 발견된 횟수 (예: 목록 27줄의 같은 칸 → 27). 예전 결과는 null → 1 */
        public int occurrenceCount() {
            return occurrences == null || occurrences < 1 ? 1 : occurrences;
        }

        /** 같은 자리에서 여러 번 되풀이된 문제인지 (1건으로 셈) */
        public boolean repeated() {
            return occurrenceCount() > 1;
        }

        /** 이 문제가 있는 페이지들. 여러 페이지에 똑같이 있는 공통 영역 문제는 1건에 페이지가 여러 개 (예전 결과는 pageUrl 하나) */
        public List<String> pageList() {
            if (pages != null && !pages.isEmpty()) return pages;
            return pageUrl == null ? List.of() : List.of(pageUrl);
        }

        /** 머리글·바닥글처럼 여러 페이지에 공통으로 있는 문제인지 */
        public boolean shared() {
            return pageList().size() > 1;
        }

        /** 이 페이지에 있는 문제인지 (공통 영역 문제는 들어 있는 모든 페이지에 해당) */
        public boolean onPage(String url) {
            return url != null && pageList().contains(url);
        }

        /** 캡처 파일 이름 (예: "p1-3.jpg"). 실행기가 "shots/<작업ID>/p1-3.jpg"로 적어 준다. */
        public String screenshotName() {
            if (screenshot == null) return null;
            String name = screenshot.substring(screenshot.lastIndexOf('/') + 1);
            return name.matches("[A-Za-z0-9-]{1,40}\\.jpg") ? name : null;
        }
    }
}
