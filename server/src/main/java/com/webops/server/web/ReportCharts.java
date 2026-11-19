package com.webops.server.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.webops.server.scan.ScanReport;

/**
 * 인쇄용(PDF) 보고서의 그래프 값.
 * 화면 그림(원형 점수판·쌓은 막대)에 넣을 비율과 숫자를 미리 계산해 둔다. 영역별 결과는 ResultView.areas().
 * 모든 값은 검사 결과에서 나온 것만 쓴다 (평균값 같은 지어낸 수치 없음).
 */
public record ReportCharts(
        Integer score,
        List<Segment> severity,
        int severityTotal,
        int urgent,
        List<Segment> pageGrades,
        List<ResultView.PlanItem> top) {

    /** 원형 점수판 둘레 (반지름 52) */
    public static final double RING_C = 2 * Math.PI * 52;

    /** 쌓은 막대의 한 칸: code(색 구분), 이름, 건수, 전체 중 비율(%) */
    public record Segment(String code, String label, int count, double pct) {
        public String widthStyle() {
            return "width: " + pct + "%;";
        }
    }

    public static ReportCharts of(ResultView view) {
        ScanReport report = view.report();
        Integer score = view.qualityScore();

        // 심각도별 건수 → 쌓은 막대
        Map<String, Integer> bySev = report.summary() == null ? Map.of() : report.summary().bySeverity();
        String[][] sevs = {{"CRITICAL", "심각"}, {"HIGH", "높음"}, {"MEDIUM", "보통"}, {"LOW", "낮음"}};
        int sevTotal = 0;
        for (String[] s : sevs) sevTotal += bySev.getOrDefault(s[0], 0);
        List<Segment> severity = new ArrayList<>();
        for (String[] s : sevs) {
            int n = bySev.getOrDefault(s[0], 0);
            severity.add(new Segment(s[0].toLowerCase(), s[1], n, pct(n, sevTotal)));
        }
        int urgent = bySev.getOrDefault("CRITICAL", 0) + bySev.getOrDefault("HIGH", 0);

        // 페이지 점수 구간별 페이지 수 (사이트 전체 점검)
        List<Segment> pageGrades = new ArrayList<>();
        if (view.multiPage()) {
            int good = 0, mid = 0, bad = 0, closed = 0;
            for (ResultView.PageRow p : view.pages()) {
                if (p.score() == null) closed++;
                else if (p.score() >= 80) good++;
                else if (p.score() >= 50) mid++;
                else bad++;
            }
            int n = view.pages().size();
            pageGrades.add(new Segment("good", "양호 (80점 이상)", good, pct(good, n)));
            pageGrades.add(new Segment("mid", "주의 (50~79점)", mid, pct(mid, n)));
            pageGrades.add(new Segment("bad", "위험 (50점 미만)", bad, pct(bad, n)));
            pageGrades.add(new Segment("closed", "열리지 않음", closed, pct(closed, n)));
        }

        List<ResultView.PlanItem> top = view.plan().stream().limit(5).toList();

        return new ReportCharts(score, severity, sevTotal, urgent, pageGrades, top);
    }

    /** 큰 원형 점수판의 채움 길이 */
    public String scoreDash() {
        return dash(score);
    }

    public String scoreGrade() {
        return grade(score);
    }

    public String scoreGradeLabel() {
        if (score == null) return "계산 안 됨";
        return score >= 80 ? "양호" : score >= 50 ? "주의" : "위험";
    }

    static String dash(Integer value) {
        double v = value == null ? 0 : Math.max(0, Math.min(100, value));
        return String.format(java.util.Locale.ROOT, "%.1f %.1f", RING_C * v / 100, RING_C);
    }

    static String grade(Integer value) {
        if (value == null) return "none";
        return value >= 80 ? "good" : value >= 50 ? "mid" : "bad";
    }

    private static double pct(long n, long total) {
        if (total <= 0) return 0;
        return Math.round(n * 1000.0 / total) / 10.0;
    }
}
