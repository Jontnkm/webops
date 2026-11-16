package com.webops.server.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.webops.server.scan.ScanReport;
import com.webops.server.scan.ScanReport.Finding;

/**
 * 결과 화면에 맞게 다듬은 데이터.
 * - checks:  검사 항목별 결과 (이상 없음 / N건 / 측정 안 함) — 누르면 아래 목록이 걸러짐
 * - metrics: 로딩 속도 지표
 * - plan:    개선 방안 (우선순위 순 할 일 목록)
 * - groups:  같은 규칙끼리 묶은 문제 목록 (직접 확인 필요 포함, 화면에서 걸러 보여 줌)
 */
public record ResultView(
        ScanReport report,
        ScanReport.PageResult page,
        List<CheckRow> checks,
        List<MetricRow> metrics,
        List<PlanItem> plan,
        List<RuleGroup> groups) {

    public static final String MANUAL = "MANUAL";

    /** 분류 이름 */
    private static final Map<String, String> CATEGORY_LABELS = Map.ofEntries(
            Map.entry("AVAILABILITY", "접속"),
            Map.entry("RESOURCE", "파일 불러오기"),
            Map.entry("API", "데이터 불러오기"),
            Map.entry("SCRIPT", "화면 동작"),
            Map.entry("MOBILE", "휴대폰 화면"),
            Map.entry("LINK", "링크"),
            Map.entry("IMAGE", "이미지"),
            Map.entry("SECURITY", "보안 연결"),
            Map.entry("ACCESSIBILITY", "접근성"),
            Map.entry("PERFORMANCE", "로딩 속도"),
            Map.entry("SEO", "검색 노출"));

    /** 분류 순서: 기능이 깨지는 문제 → 이용 편의 → 속도·검색 노출 (같은 심각도일 때 우선순위) */
    private static final List<String> CATEGORY_ORDER = List.of(
            "AVAILABILITY", "RESOURCE", "API", "SCRIPT", "MOBILE", "LINK", "IMAGE", "SECURITY",
            "ACCESSIBILITY", "PERFORMANCE", "SEO");

    private static final List<String> SEVERITY_ORDER = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    /** 검사 항목별 결과표: 코드, 이름, 무엇을 봤는지 */
    private static final List<String[]> CHECK_ITEMS = List.of(
            new String[] {"AVAILABILITY", "페이지 접속", "페이지가 열리는지"},
            new String[] {"RESOURCE", "파일 불러오기", "CSS·JS·글꼴 파일을 다 불러오는지"},
            new String[] {"API", "데이터 불러오기", "목록·팝업 데이터 요청이 성공하는지"},
            new String[] {"SCRIPT", "화면 동작", "스크립트 오류가 없는지"},
            new String[] {"MOBILE", "휴대폰 화면", "가로 360px에서 밀리지 않는지"},
            new String[] {"LINK", "링크", "링크가 제대로 열리는지"},
            new String[] {"IMAGE", "이미지", "이미지가 엑스박스 없이 보이는지"},
            new String[] {"SECURITY", "보안 연결", "https 페이지에 http 파일이 없는지"},
            new String[] {"ACCESSIBILITY", "접근성", "장애인·고령자도 이용할 수 있는지"},
            new String[] {"PERFORMANCE", "로딩 속도", "빨리 뜨고 화면이 밀리지 않는지"},
            new String[] {"SEO", "검색 노출", "검색엔진에 잘 나오는지"});

    /**
     * 속도 지표: key, 이름, 쉬운 설명, 좋음 기준, 보통 기준, 단위(ms / score)
     * 기준값은 Lighthouse PC(desktop) 평가 기준 (실제 웹사이트 측정 자료 HTTP Archive 기반).
     * 좋음 기준보다 작으면 90점 이상, 보통 기준이면 50점.
     */
    private static final List<Object[]> METRIC_ITEMS = List.of(
            new Object[] {"fcp", "첫 화면이 보이기까지", "글자나 그림이 처음 나타나는 시간", 934.0, 1600.0, "sec"},
            new Object[] {"lcp", "주요 내용이 다 보이기까지", "메인 배너처럼 가장 큰 요소가 나타나는 시간", 1200.0, 2400.0, "sec"},
            new Object[] {"speedIndex", "화면이 채워지는 속도", "보이는 영역이 내용으로 채워지는 시간", 1311.0, 2300.0, "sec"},
            new Object[] {"tbt", "화면이 멈춰 있는 시간", "로딩 중 눌러도 반응하지 않는 시간", 150.0, 350.0, "ms"},
            new Object[] {"cls", "로딩 중 화면 밀림", "내용이 갑자기 밀리는 정도 (0에 가까울수록 좋음)", 0.1, 0.25, "score"});

    private static final Map<String, String> SEVERITY_LABELS = Map.of(
            "CRITICAL", "심각", "HIGH", "높음", "MEDIUM", "보통", "LOW", "낮음");

    private static final Map<String, String> RATING_LABELS = Map.of(
            "GOOD", "좋음", "NEEDS_IMPROVEMENT", "개선 필요", "POOR", "나쁨");

    public static ResultView of(ScanReport report) {
        List<Finding> findings = report.findings() == null ? List.of() : report.findings();
        ScanReport.PageResult page = report.firstPage();

        // 같은 규칙끼리 묶기 (직접 확인 필요 항목은 따로 묶음)
        Map<String, List<Finding>> grouped = new LinkedHashMap<>();
        for (Finding f : findings) {
            grouped.computeIfAbsent(f.manualCheck() + "|" + f.source() + "|" + f.ruleId(), k -> new ArrayList<>()).add(f);
        }
        List<RuleGroup> groups = new ArrayList<>();
        for (List<Finding> items : grouped.values()) {
            groups.add(toGroup(items));
        }
        // 심각도 → 분류 순서 → 건수 많은 순
        groups.sort(Comparator
                .comparingInt((RuleGroup g) -> rank(SEVERITY_ORDER, g.severity()))
                .thenComparingInt(g -> rank(CATEGORY_ORDER, g.category()))
                .thenComparing(Comparator.comparingInt(RuleGroup::count).reversed()));
        // 화면에서 찾아갈 수 있게 순서대로 번호를 붙인다
        List<RuleGroup> numbered = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            numbered.add(groups.get(i).withId("g" + (i + 1)));
        }

        List<PlanItem> plan = new ArrayList<>();
        for (RuleGroup g : numbered) {
            if (!g.manual()) {
                plan.add(new PlanItem(plan.size() + 1, g.id(), g.severity(), g.severityLabel(),
                        g.category(), g.categoryLabel(), g.action(), g.owner(), g.count()));
            }
        }

        return new ResultView(report, page, buildChecks(findings, page), buildMetrics(page, numbered), plan, numbered);
    }

    private static RuleGroup toGroup(List<Finding> items) {
        Finding first = items.get(0);
        RuleGuide.Guide guide = RuleGuide.find(first.ruleId());
        String category = first.category();
        String categoryLabel = CATEGORY_LABELS.getOrDefault(category, category);
        String title = guide != null ? guide.title() : first.description();
        String action = guide != null ? guide.action() : "'" + title + "' 개선";
        String owner = guide != null ? guide.owner() : defaultOwner(category);
        String why = guide != null ? guide.why() : null;
        String fix = guide != null ? guide.fix() : "아래 '자세한 설명' 링크와 문제가 된 코드를 참고해 고쳐 주세요.";
        String example = guide != null ? guide.example() : null;
        String help = first.helpUrl() != null && first.helpUrl().startsWith("https://") ? first.helpUrl() : null;
        if (first.manualCheck()) {
            title = title.startsWith("[직접 확인 필요]") ? title : "[직접 확인 필요] " + title;
        }
        return new RuleGroup(null, first.severity(), SEVERITY_LABELS.getOrDefault(first.severity(), first.severity()),
                category, categoryLabel, first.ruleId(), first.manualCheck(),
                title, action, owner, why, fix, example, help, items);
    }

    private static String defaultOwner(String category) {
        return switch (category) {
            case "API", "SCRIPT" -> RuleGuide.DEV;
            case "AVAILABILITY" -> RuleGuide.SERVER;
            default -> RuleGuide.PUB;
        };
    }

    /** 검사 항목별로 "이상 없음 / N건 / 측정 안 함"을 정리한다. */
    private static List<CheckRow> buildChecks(List<Finding> findings, ScanReport.PageResult page) {
        boolean pageLoaded = page != null && page.httpStatus() != null;
        boolean lighthouse = page != null && page.scores() != null;
        List<CheckRow> rows = new ArrayList<>();
        for (String[] item : CHECK_ITEMS) {
            String code = item[0];
            long count = findings.stream().filter(inCategory(code)).count();
            boolean measured = switch (code) {
                case "AVAILABILITY" -> true;
                case "MOBILE" -> page != null && page.mobile() != null;
                case "LINK" -> page != null && page.linksChecked() != null;
                case "PERFORMANCE", "SEO" -> lighthouse;
                // https 페이지에서만 의미가 있는 검사
                case "SECURITY" -> pageLoaded && page.pageUrl() != null && page.pageUrl().startsWith("https://");
                default -> pageLoaded;
            };
            String note = switch (code) {
                case "LINK" -> page == null || page.linksChecked() == null ? null
                        : page.linkCount() != null && page.linksChecked() < page.linkCount()
                                ? "링크 " + page.linkCount() + "개 중 " + page.linksChecked() + "개 확인"
                                : "링크 " + page.linksChecked() + "개 확인";
                case "IMAGE" -> page == null || page.imageCount() == null ? null : "이미지 " + page.imageCount() + "개 확인";
                case "PERFORMANCE" -> lighthouse && page.scores().performance() != null ? page.scores().performance() + "점 / 100" : null;
                case "SEO" -> lighthouse && page.scores().seo() != null ? page.scores().seo() + "점 / 100" : null;
                case "SECURITY" -> page != null && page.pageUrl() != null && page.pageUrl().startsWith("http://")
                        ? "https를 쓰지 않는 페이지" : null;
                default -> null;
            };
            String status = !measured ? ("SECURITY".equals(code) && pageLoaded ? "해당 없음" : "측정 안 함")
                    : count == 0 ? "이상 없음" : count + "건";
            rows.add(new CheckRow(code, item[1], item[2], count, measured, status, note));
        }
        return rows;
    }

    /** 지표별 일반적인 개선 방법 */
    private static final Map<String, List<String>> METRIC_TIPS = Map.of(
            "fcp", List.of(
                    "첫 화면에 필요 없는 스크립트(JS)는 defer를 붙여 나중에 불러오세요.",
                    "CSS 안의 @import 대신 HTML에서 바로 불러오고, 쓰지 않는 CSS를 줄이세요.",
                    "웹 글꼴에 font-display: swap을 지정해 글꼴을 받는 동안에도 글자가 보이게 하세요.",
                    "메인 페이지의 서버 처리 시간(쿼리·리다이렉트)을 줄이세요."),
            "lcp", List.of(
                    "메인 배너 이미지를 보이는 크기에 맞게 줄이고 WebP 등으로 압축하세요.",
                    "메인 배너는 CSS 배경 대신 img로 넣고 <link rel=\"preload\">로 미리 불러오세요.",
                    "메인 배너에는 loading=\"lazy\"를 쓰지 마세요.",
                    "화면 표시를 막는 CSS·JS를 줄이세요."),
            "speedIndex", List.of(
                    "첫 화면(스크롤 없이 보이는 영역)의 이미지·글꼴을 먼저 불러오세요.",
                    "화면 아래쪽 이미지는 loading=\"lazy\"로 나중에 불러오세요.",
                    "화면 표시를 막는 CSS·JS를 줄이세요."),
            "tbt", List.of(
                    "쓰지 않는 스크립트·플러그인을 빼세요 (중복으로 불러오는 jQuery 등).",
                    "통계·광고·SNS 같은 외부 스크립트는 async·defer로 나중에 실행하세요.",
                    "무거운 슬라이드·애니메이션 스크립트는 화면이 다 뜬 뒤 시작하세요.",
                    "숨겨 둔 전체 메뉴·탭 내용 등 처음에 안 보이는 HTML을 줄이세요."),
            "cls", List.of(
                    "이미지·배너에 width·height 또는 aspect-ratio를 지정해 자리를 미리 잡으세요.",
                    "늦게 뜨는 띠배너·팝업·공지 영역은 min-height로 자리를 확보하세요.",
                    "이미 보이는 내용 위에 요소를 끼워 넣지 마세요.",
                    "웹 글꼴은 font-display: swap과 비슷한 기본 글꼴을 함께 지정해 글자 크기 변화를 줄이세요."));

    /** 지표별로 관련 있는 로딩 속도 원인 (Lighthouse 항목 이름) */
    private static final Map<String, List<String>> METRIC_AUDITS = Map.of(
            "fcp", List.of("render-blocking-insight", "font-display-insight", "document-latency-insight",
                    "network-dependency-tree-insight", "unused-css-rules", "cache-insight", "total-byte-weight"),
            "lcp", List.of("lcp-discovery-insight", "lcp-phases-insight", "image-delivery-insight",
                    "render-blocking-insight", "document-latency-insight", "cache-insight", "total-byte-weight"),
            "speedIndex", List.of("render-blocking-insight", "font-display-insight", "image-delivery-insight",
                    "unused-javascript", "bootup-time"),
            "tbt", List.of("third-parties-insight", "bootup-time", "mainthread-work-breakdown", "unused-javascript",
                    "duplicated-javascript-insight", "legacy-javascript-insight", "forced-reflow-insight", "dom-size-insight"),
            "cls", List.of("cls-culprits-insight", "layout-shifts", "unsized-images", "font-display-insight"));

    /** 속도 지표를 그래프로 그릴 수 있게 위치(%)를 계산하고, 개선 방법과 관련 원인을 붙인다. */
    private static List<MetricRow> buildMetrics(ScanReport.PageResult page, List<RuleGroup> groups) {
        if (page == null || page.metricDetails() == null) return List.of();
        List<MetricRow> rows = new ArrayList<>();
        for (Object[] item : METRIC_ITEMS) {
            String key = (String) item[0];
            double good = (Double) item[3];
            double poor = (Double) item[4];
            String unit = (String) item[5];
            List<String> audits = METRIC_AUDITS.getOrDefault(key, List.of());
            List<RuleGroup> related = groups.stream()
                    .filter(g -> "PERFORMANCE".equals(g.category()) && audits.contains(g.ruleId()))
                    .toList();
            page.metricDetails().stream().filter(m -> key.equals(m.key())).findFirst().ifPresent(m -> {
                Double value = m.numericValue();
                // 그래프 오른쪽 끝: 보통 기준의 1.6배, 측정값이 더 크면 측정값보다 조금 더 길게
                double max = Math.max(poor * 1.6, value == null ? 0 : value * 1.15);
                rows.add(new MetricRow(
                        key, (String) item[1], (String) item[2],
                        value == null ? (m.displayValue() == null ? "-" : m.displayValue()) : format(value, unit),
                        m.rating() == null ? "NONE" : m.rating(),
                        RATING_LABELS.getOrDefault(m.rating(), "-"),
                        value == null ? null : pct(value, max),
                        pct(good, max), pct(poor, max),
                        format(good, unit), format(poor, unit),
                        statusText(m.rating(), value, good, unit),
                        METRIC_TIPS.getOrDefault(key, List.of()), related));
            });
        }
        return rows;
    }

    /** 현재 상태를 한 문장으로 */
    private static String statusText(String rating, Double value, double good, String unit) {
        if (rating == null || value == null) return "측정값을 확인하지 못했습니다.";
        return switch (rating) {
            case "GOOD" -> "좋음 기준 안쪽입니다. 지금 상태를 유지하면 됩니다.";
            case "NEEDS_IMPROVEMENT" -> "좋음 기준까지 " + format(value - good, unit) + " 더 줄이면 '좋음'이 됩니다.";
            default -> "보통 기준보다 느립니다. 아래 방법으로 개선이 필요합니다. (좋음 기준까지 " + format(value - good, unit) + ")";
        };
    }

    private static double pct(double v, double max) {
        return Math.round(Math.min(100, Math.max(0, v / max * 100)) * 10) / 10.0;
    }

    /**
     * 단위에 맞춰 보기 좋게: 초 단위 지표는 항상 초로 (245ms → "0.25초", 1340ms → "1.3초"),
     * 멈춰 있는 시간은 ms ("20ms"), 화면 밀림은 숫자 그대로 ("0.999")
     */
    private static String format(double v, String unit) {
        if ("score".equals(unit)) {
            return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.3f", v).replaceAll("0+$", "");
        }
        if ("sec".equals(unit)) {
            return v < 1000
                    ? String.format("%.2f", v / 1000).replaceAll("0$", "") + "초"
                    : String.format("%.1f초", v / 1000);
        }
        return Math.round(v) + "ms";
    }

    // ---------- 화면에서 쓰는 쉬운 표현 ----------

    /** 페이지 접속 상태: "정상" / "없는 페이지" / "서버 오류" / "접속 안 됨" */
    public String accessLabel() {
        Integer code = page == null ? null : page.httpStatus();
        if (code == null) return "접속 안 됨";
        if (code < 400) return "정상";
        if (code == 404) return "없는 페이지";
        if (code >= 500) return "서버 오류";
        return "열리지 않음";
    }

    public boolean accessOk() {
        return page != null && page.httpStatus() != null && page.httpStatus() < 400;
    }

    /** 휴대폰 화면 상태: "정상" / "옆으로 밀림" / "확인 못 함" */
    public String mobileLabel() {
        ScanReport.Mobile m = page == null ? null : page.mobile();
        if (m == null || m.scrollWidth() == null || m.viewportWidth() == null) return "확인 못 함";
        return m.scrollWidth() - m.viewportWidth() > 1 ? "옆으로 밀림" : "정상";
    }

    public boolean mobileOk() {
        return "정상".equals(mobileLabel());
    }

    public long manualCount() {
        return groups.stream().filter(RuleGroup::manual).mapToLong(RuleGroup::count).sum();
    }

    public long issueCount() {
        return groups.stream().filter(g -> !g.manual()).mapToLong(RuleGroup::count).sum();
    }

    private static Predicate<Finding> inCategory(String code) {
        return f -> !f.manualCheck() && code.equals(f.category());
    }

    private static int rank(List<String> order, String value) {
        int i = order.indexOf(value);
        return i < 0 ? order.size() : i;
    }

    /** 검사 항목별 결과표의 한 줄 */
    public record CheckRow(String code, String label, String what, long count, boolean measured, String status, String note) {
    }

    /**
     * 로딩 속도 지표 한 줄 (그래프용)
     * rating: GOOD / NEEDS_IMPROVEMENT / POOR / NONE
     * valuePct·goodPct·poorPct: 그래프 왼쪽 끝을 0, 오른쪽 끝을 100으로 본 위치
     */
    public record MetricRow(String key, String label, String what, String value, String rating, String ratingLabel,
                            Double valuePct, double goodPct, double poorPct, String goodLabel, String poorLabel,
                            String status, List<String> tips, List<RuleGroup> related) {

        /** 막대 배경: 좋음(초록) → 개선 필요(노랑) → 나쁨(빨강) 구간 */
        public String zoneStyle() {
            return "background: linear-gradient(to right, "
                    + "var(--zone-good) 0% " + goodPct + "%, "
                    + "var(--zone-mid) " + goodPct + "% " + poorPct + "%, "
                    + "var(--zone-bad) " + poorPct + "% 100%);";
        }

        public String valueStyle() {
            return "left: " + (valuePct == null ? 0 : valuePct) + "%;";
        }

        public String goodStyle() {
            return "left: " + goodPct + "%;";
        }

        public String poorStyle() {
            return "left: " + poorPct + "%;";
        }
    }

    // ---------- 검사 항목별 결과를 구역별로 ----------

    public List<CheckRow> checksWithIssues() {
        return checks.stream().filter(c -> c.measured() && c.count() > 0).toList();
    }

    public List<CheckRow> checksOk() {
        return checks.stream().filter(c -> c.measured() && c.count() == 0).toList();
    }

    public List<CheckRow> checksSkipped() {
        return checks.stream().filter(c -> !c.measured()).toList();
    }

    /** 개선 방안 한 줄: 할 일, 담당, 몇 곳 */
    public record PlanItem(int rank, String groupId, String severity, String severityLabel,
                           String category, String categoryLabel, String action, String owner, int count) {
    }

    public record RuleGroup(
            String id,
            String severity,
            String severityLabel,
            String category,
            String categoryLabel,
            String ruleId,
            boolean manual,
            String title,
            String action,
            String owner,
            String why,
            String fix,
            String example,
            String helpUrl,
            List<Finding> items) {

        public int count() {
            return items.size();
        }

        /** 화면 필터용 분류 코드 (직접 확인 필요는 MANUAL) */
        public String filterCode() {
            return manual ? MANUAL : category;
        }

        /** 예시를 "고치기 전 / 고친 후"로 나눈다 (→ 기준) */
        public String exampleBefore() {
            if (example == null || !example.contains("\n→ ")) return null;
            return example.substring(0, example.indexOf("\n→ "));
        }

        public String exampleAfter() {
            if (example == null) return null;
            int i = example.indexOf("\n→ ");
            return i < 0 ? example : example.substring(i + 3);
        }

        RuleGroup withId(String newId) {
            return new RuleGroup(newId, severity, severityLabel, category, categoryLabel, ruleId, manual,
                    title, action, owner, why, fix, example, helpUrl, items);
        }
    }
}
