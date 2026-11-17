package com.webops.server.scan;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WebOps 품질 점수 (WebOps가 정한 계산식, Lighthouse 점수와 다름).
 * - 페이지 점수: 100점에서 문제 종류마다 심각도별 점수를 뺀다. 같은 문제가 여러 곳에 있어도 한 번만 (그 문제의 가장 높은 심각도).
 * - 검사 점수: 페이지 점수의 평균. 열리지 않은 페이지는 0점.
 * 결과 화면 · PDF · 추이 그래프가 모두 이 계산을 쓴다.
 */
public final class QualityScore {

    public static final Map<String, Integer> PENALTY = Map.of("CRITICAL", 20, "HIGH", 10, "MEDIUM", 4, "LOW", 1);

    public static final String RULE = "문제 종류마다 심각 20점 · 높음 10점 · 보통 4점 · 낮음 1점을 100점에서 뺍니다 (같은 문제가 여러 곳에 있어도 한 번만).";

    private QualityScore() {
    }

    /** 한 페이지의 발견 항목 → 점수 (직접 확인 필요 항목은 빼고 계산) */
    public static int pageScore(Collection<ScanReport.Finding> pageFindings) {
        Map<String, Integer> worst = new HashMap<>();
        for (ScanReport.Finding f : pageFindings) {
            if (f.manualCheck()) continue;
            worst.merge(f.source() + "|" + f.ruleId(), PENALTY.getOrDefault(f.severity(), 0), Math::max);
        }
        int penalty = worst.values().stream().mapToInt(Integer::intValue).sum();
        return Math.max(0, 100 - penalty);
    }

    /** 페이지가 열렸는지 (열리지 않은 페이지는 점수 없음) */
    public static boolean opened(ScanReport.PageResult page) {
        return page.httpStatus() != null && page.httpStatus() < 400;
    }

    /** 페이지 하나의 점수 (열리지 않았으면 null) */
    public static Integer pageScore(ScanReport report, ScanReport.PageResult page) {
        if (!opened(page)) return null;
        List<ScanReport.Finding> own = findings(report).stream()
                .filter(f -> f.onPage(page.pageUrl()))
                .toList();
        return pageScore(own);
    }

    /** 검사 전체 점수: 페이지 점수 평균 (열리지 않은 페이지는 0점) */
    public static Integer scanScore(ScanReport report) {
        if (report.pages() == null || report.pages().isEmpty()) return null;
        int sum = 0;
        for (ScanReport.PageResult p : report.pages()) {
            Integer s = pageScore(report, p);
            sum += s == null ? 0 : s;
        }
        return Math.round((float) sum / report.pages().size());
    }

    private static List<ScanReport.Finding> findings(ScanReport report) {
        return report.findings() == null ? List.of() : report.findings();
    }
}
