package com.webops.server.site;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import com.webops.server.store.ScanJobEntity;

/**
 * 품질 추이 그래프 데이터. 좌표는 그래프 영역 기준 0~100(%)으로 계산한다.
 * 선은 SVG(늘어나도 굵기 유지), 점·눈금 글자는 HTML로 % 위치에 놓아 좁은 화면에서도 글자가 작아지지 않게 한다.
 * - 그래프마다 측정값 하나 (축이 두 개인 그래프는 만들지 않음): 품질 점수 / 발견된 문제 / 로딩 속도 점수
 * - 끝난 검사 중 최근 30건, 왼쪽이 오래된 검사
 */
public record TrendView(List<Chart> charts, List<Row> rows) {

    static final int W = 100;
    static final int H = 100;
    static final int PAD_L = 3;
    static final int PAD_R = 3;
    static final int PAD_T = 0;
    static final int PAD_B = 0;
    static final int MAX_POINTS = 30;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 그래프 하나. refs: 기준선(점수 구간 등), xLabels: 아래 날짜 */
    public record Chart(String key, String title, String desc, String unit, List<Point> points, String path,
                        List<Tick> grid, List<Tick> refs, List<Tick> xLabels, Point last) {

        public int width() { return W; }
        public int height() { return H; }
        public int left() { return PAD_L; }
        public int right() { return W - PAD_R; }
        public int bottom() { return H - PAD_B; }
        public String viewBox() { return "0 0 " + W + " " + H; }
        public boolean single() { return points.size() == 1; }
    }

    /** 점 하나: 좌표, 값, 날짜, 그 검사 번호(누르면 결과 화면으로) */
    public record Point(double x, double y, String value, String date, String jobId) {
        public String tooltip() { return date + " · " + value; }
    }

    /** 눈금: 위치(가로 그래프면 y, 날짜면 x)와 글자 */
    public record Tick(double pos, String label) {
    }

    /** 검사 기록 표 한 줄 */
    public record Row(ScanJobEntity job, Integer scoreDelta, Integer issueDelta) {
    }

    public static TrendView of(List<ScanJobEntity> historyOldestFirst) {
        List<ScanJobEntity> done = historyOldestFirst.stream()
                .filter(j -> "DONE".equals(j.getStatus()))
                .toList();
        List<ScanJobEntity> recent = done.subList(Math.max(0, done.size() - MAX_POINTS), done.size());

        List<Chart> charts = new ArrayList<>();
        if (!recent.isEmpty()) {
            charts.add(chart("score", "품질 점수", "100점 만점 · WebOps 계산식 (80점 이상 양호, 50~79점 주의)", "점",
                    recent, ScanJobEntity::getQualityScore, 100,
                    List.of(new double[] {80, 0}, new double[] {50, 1})));
            charts.add(chart("issues", "발견된 문제", "직접 확인 필요 항목은 빼고 셈", "건",
                    recent, ScanJobEntity::getIssueCount, 0, List.of()));
            if (recent.stream().anyMatch(j -> j.getPerfScore() != null)) {
                charts.add(chart("perf", "로딩 속도 점수", "Lighthouse PC 기준 · 첫 페이지 · 측정한 검사만", "점",
                        recent, ScanJobEntity::getPerfScore, 100, List.of()));
            }
        }

        // 표: 최신 검사가 위. 바로 전 끝난 검사와 비교한 점수·건수 변화
        List<Row> rows = new ArrayList<>();
        ScanJobEntity prevDone = null;
        List<Row> oldestFirst = new ArrayList<>();
        for (ScanJobEntity j : historyOldestFirst) {
            Integer scoreDelta = null;
            Integer issueDelta = null;
            if ("DONE".equals(j.getStatus()) && prevDone != null) {
                scoreDelta = diff(j.getQualityScore(), prevDone.getQualityScore());
                issueDelta = diff(j.getIssueCount(), prevDone.getIssueCount());
            }
            oldestFirst.add(new Row(j, scoreDelta, issueDelta));
            if ("DONE".equals(j.getStatus())) prevDone = j;
        }
        for (int i = oldestFirst.size() - 1; i >= 0; i--) rows.add(oldestFirst.get(i));
        return new TrendView(charts, rows);
    }

    /**
     * @param fixedMax 0이면 값에 맞춰 위쪽 끝을 정한다
     * @param refs 기준선 {값, 종류(0 양호 · 1 주의)}
     */
    private static Chart chart(String key, String title, String desc, String unit, List<ScanJobEntity> jobs,
                               Function<ScanJobEntity, Integer> value, int fixedMax, List<double[]> refs) {
        List<ScanJobEntity> withValue = jobs.stream().filter(j -> value.apply(j) != null).toList();
        int max = fixedMax > 0 ? fixedMax
                : niceMax(withValue.stream().mapToInt(j -> value.apply(j)).max().orElse(0));
        double plotW = W - PAD_L - PAD_R;
        double plotH = H - PAD_T - PAD_B;
        int n = jobs.size();

        List<Point> points = new ArrayList<>();
        List<Tick> xLabels = new ArrayList<>();
        StringBuilder path = new StringBuilder();
        int labelEvery = Math.max(1, (int) Math.ceil(n / 6.0));
        for (int i = 0; i < n; i++) {
            ScanJobEntity j = jobs.get(i);
            double x = n == 1 ? PAD_L + plotW / 2 : PAD_L + plotW * i / (n - 1);
            if (i % labelEvery == 0 || i == n - 1) {
                // 마지막 날짜와 겹치는 바로 앞 날짜는 뺀다
                if (i == n - 1 && !xLabels.isEmpty() && x - xLabels.get(xLabels.size() - 1).pos() < 9) {
                    xLabels.remove(xLabels.size() - 1);
                }
                xLabels.add(new Tick(round(x), j.getCreatedAt().format(DAY)));
            }
            Integer v = value.apply(j);
            if (v == null) continue;
            double y = PAD_T + plotH * (1 - Math.min(v, max) / (double) max);
            path.append(path.isEmpty() ? "M" : " L").append(round(x)).append(' ').append(round(y));
            points.add(new Point(round(x), round(y), v + unit, j.getCreatedAt().format(FULL), j.getJobId()));
        }

        List<Tick> grid = new ArrayList<>();
        for (int k = 0; k <= 4; k++) {
            int gv = max * k / 4;
            grid.add(new Tick(round(PAD_T + plotH * (1 - gv / (double) max)), String.valueOf(gv)));
        }
        List<Tick> refTicks = refs.stream()
                .map(r -> new Tick(round(PAD_T + plotH * (1 - r[0] / max)), r[1] == 0 ? "양호 " + (int) r[0] : "주의 " + (int) r[0]))
                .toList();
        Point last = points.isEmpty() ? null : points.get(points.size() - 1);
        return new Chart(key, title, desc, unit, points, path.toString(), grid, refTicks, xLabels, last);
    }

    /** 위쪽 끝을 보기 좋은 수로 (7 → 10, 23 → 25, 140 → 150) */
    static int niceMax(int v) {
        if (v <= 4) return 4;
        int[] steps = {1, 2, 5};
        int base = 1;
        while (true) {
            for (int s : steps) {
                int candidate = s * base * 4;
                if (candidate >= v) return candidate;
            }
            base *= 10;
        }
    }

    private static Integer diff(Integer a, Integer b) {
        return a == null || b == null ? null : a - b;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    public boolean empty() {
        return charts.isEmpty();
    }

    /** 표에서 쓰는 검사 종류 이름 */
    public static String triggerLabel(ScanJobEntity j) {
        return Objects.equals(j.getTriggerType(), "SCHEDULED") ? "정기" : "직접";
    }
}
