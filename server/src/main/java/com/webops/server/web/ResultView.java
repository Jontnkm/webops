package com.webops.server.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.webops.server.scan.Comparison;
import com.webops.server.scan.QualityScore;
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
        List<RuleGroup> groups,
        List<PageRow> pages,
        Comparison comparison) {

    public static final String MANUAL = "MANUAL";

    /** 분류(세부 항목) 이름: 무슨 문제인지 이름에 드러나게 */
    private static final Map<String, String> CATEGORY_LABELS = Map.ofEntries(
            Map.entry("AVAILABILITY", "페이지 접속"),
            Map.entry("LINK", "깨진 링크"),
            Map.entry("IMAGE", "깨진 이미지"),
            Map.entry("RESOURCE", "파일 불러오기 오류"),
            Map.entry("API", "데이터 불러오기 오류"),
            Map.entry("SCRIPT", "스크립트 오류"),
            Map.entry("SECURITY", "보안"),
            Map.entry("MOBILE", "모바일 환경"),
            Map.entry("ACCESSIBILITY", "접근성"),
            Map.entry("PERFORMANCE", "로딩 속도"),
            Map.entry("SEO", "SEO"),
            Map.entry("ESS_PRIVACY", "개인정보처리방침"),
            Map.entry("ESS_TERMS", "이용약관"),
            Map.entry("ESS_COPYRIGHT", "저작권 표시"));

    /** 분류 순서: 기능 결함 → 접근성 → 로딩 속도 → SEO (같은 심각도일 때 우선순위) */
    private static final List<String> CATEGORY_ORDER = List.of(
            "AVAILABILITY", "LINK", "IMAGE", "RESOURCE", "API", "SCRIPT", "SECURITY", "MOBILE",
            "ACCESSIBILITY", "PERFORMANCE", "SEO", "ESS_PRIVACY", "ESS_TERMS", "ESS_COPYRIGHT");

    /** 검사 영역 4가지: 코드, 이름, 무엇을 보는지 */
    private static final List<String[]> AREA_ITEMS = List.of(
            new String[] {"FUNCTION", "기능 결함", "사이트가 제대로 작동하는지"},
            new String[] {"ACCESSIBILITY", "접근성", "장애인·고령자도 이용할 수 있는지 (KWCAG 2.2)"},
            new String[] {"PERFORMANCE", "로딩 속도", "화면이 빨리 뜨는지 (Lighthouse 성능)"},
            new String[] {"SEO", "SEO", "검색엔진에 잘 나오는지 (Lighthouse SEO)"},
            new String[] {"SECURITY", "보안", "안전하게 접속되는지 (https · 보안 설정 · 보안 인증서)"},
            new String[] {"MOBILE", "모바일 환경", "휴대폰에서 잘 보이는지 (가로 360px)"},
            new String[] {"ESSENTIAL", "필수 요소", "공공 웹사이트에 꼭 있어야 할 안내가 있는지"});

    /** 점검 종류 → 보여 줄 영역 하나 (종합점검은 없음 = 모든 영역) */
    private static final Map<String, String> AREA_BY_CHECK = Map.of(
            "function", "FUNCTION", "a11y", "ACCESSIBILITY", "perf", "PERFORMANCE",
            "seo", "SEO", "security", "SECURITY", "mobile", "MOBILE");

    /** 분류 → 영역. 접근성·로딩 속도·SEO는 분류가 곧 영역, 나머지는 모두 기능 결함 */
    public static String areaOf(String category) {
        if (category == null) return "FUNCTION";
        return switch (category) {
            case "ACCESSIBILITY", "PERFORMANCE", "SEO", "SECURITY", "MOBILE" -> category;
            case "ESS_PRIVACY", "ESS_TERMS", "ESS_COPYRIGHT" -> "ESSENTIAL";
            default -> "FUNCTION";
        };
    }

    private static final List<String> SEVERITY_ORDER = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    /** 검사 항목별 결과표: 코드, 이름, 무엇을 봤는지 */
    private static final List<String[]> CHECK_ITEMS = List.of(
            new String[] {"AVAILABILITY", "페이지 접속", "페이지가 오류 없이 열리는지"},
            new String[] {"LINK", "깨진 링크", "누르면 없는 페이지로 가는 링크가 없는지"},
            new String[] {"IMAGE", "깨진 이미지", "엑스박스로 나오는 그림이 없는지"},
            new String[] {"RESOURCE", "파일 불러오기 오류", "화면에 필요한 CSS·JS·글꼴 파일을 다 불러오는지"},
            new String[] {"API", "데이터 불러오기 오류", "목록·팝업 데이터 요청이 성공하는지"},
            new String[] {"SCRIPT", "스크립트 오류", "메뉴·버튼 동작을 멈추는 스크립트 오류가 없는지"},
            new String[] {"SECURITY", "보안", "https · 보안 설정 · 보안 인증서가 안전한지"},
            new String[] {"MOBILE", "모바일 환경", "휴대폰에서 밀림 · 확대 막음 · 작은 글자 없이 잘 보이는지"},
            new String[] {"ACCESSIBILITY", "접근성", "장애인·고령자도 이용할 수 있는지 (KWCAG 2.2)"},
            new String[] {"PERFORMANCE", "로딩 속도", "빨리 뜨고 화면이 밀리지 않는지 (Lighthouse 성능)"},
            new String[] {"SEO", "SEO", "검색엔진에 잘 나오는지 (Lighthouse SEO)"},
            new String[] {"ESS_PRIVACY", "개인정보처리방침", "개인정보처리방침 링크가 있는지 (개인정보 보호법)"},
            new String[] {"ESS_TERMS", "이용약관", "이용약관 링크가 있는지 (회원·서비스가 있는 사이트)"},
            new String[] {"ESS_COPYRIGHT", "저작권 표시", "Copyright ⓒ 같은 저작권 표시가 있는지"});

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
        return of(report, null);
    }

    /** @param comparison 지난 검사와 비교한 결과 (지난 검사가 없으면 null) */
    public static ResultView of(ScanReport report, Comparison comparison) {
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

        return new ResultView(report, page, buildChecks(findings, report), buildMetrics(page, numbered), plan, numbered,
                buildPages(report, numbered), comparison);
    }

    // ---------- 오른쪽 위 걸러 보기 · 정렬 선택 상자 ----------

    /** 선택 상자의 선택지 하나: 값, 이름, 건수 */
    public record FilterOption(String code, String label, long count) {
    }

    /** 유형 선택 상자의 영역 묶음(optgroup): 영역 이름, 영역 전체 선택지(세부 항목이 2개 이상일 때), 세부 항목 선택지 */
    public record OptionGroup(String label, FilterOption all, List<FilterOption> options) {
    }

    /** 유형 선택지를 영역별로 묶는다 (이 검사에서 나온 분류만). 직접 확인 필요는 맨 뒤 '기타'. */
    public List<OptionGroup> typeOptionGroups() {
        List<FilterOption> flat = typeOptions();
        List<OptionGroup> out = new ArrayList<>();
        for (String[] a : AREA_ITEMS) {
            List<FilterOption> in = flat.stream().filter(o -> !MANUAL.equals(o.code()) && a[0].equals(areaOf(o.code()))).toList();
            if (in.isEmpty()) continue;
            FilterOption all = in.size() > 1
                    ? new FilterOption("AREA:" + a[0], a[1] + " 전체", in.stream().mapToLong(FilterOption::count).sum())
                    : null;
            out.add(new OptionGroup(a[1], all, in));
        }
        flat.stream().filter(o -> MANUAL.equals(o.code())).findFirst()
                .ifPresent(m -> out.add(new OptionGroup("기타", null, List.of(m))));
        return out;
    }

    /** 유형 선택지: 이 검사에서 나온 분류만 (기능 결함 → 접근성 → 로딩 속도 → SEO 순), 직접 확인 필요는 맨 뒤 */
    public List<FilterOption> typeOptions() {
        List<FilterOption> options = new ArrayList<>();
        for (String code : CATEGORY_ORDER) {
            long n = groups.stream().filter(g -> !g.manual() && code.equals(g.category())).mapToLong(RuleGroup::count).sum();
            if (n > 0) options.add(new FilterOption(code, CATEGORY_LABELS.getOrDefault(code, code), n));
        }
        // 정해진 순서에 없는 분류가 생겨도 빠뜨리지 않는다
        groups.stream().filter(g -> !g.manual() && !CATEGORY_ORDER.contains(g.category())).map(RuleGroup::category).distinct()
                .forEach(code -> options.add(new FilterOption(code, CATEGORY_LABELS.getOrDefault(code, code),
                        groups.stream().filter(g -> !g.manual() && code.equals(g.category())).mapToLong(RuleGroup::count).sum())));
        long manual = manualCount();
        if (manual > 0) options.add(new FilterOption(MANUAL, "직접 확인 필요", manual));
        return options;
    }

    /** 심각도 선택지 (네 가지 모두, 0건이면 화면에서 고를 수 없게) */
    public List<FilterOption> severityOptions() {
        List<FilterOption> options = new ArrayList<>();
        for (String sev : SEVERITY_ORDER) {
            long n = groups.stream().filter(g -> !g.manual() && sev.equals(g.severity())).mapToLong(RuleGroup::count).sum();
            options.add(new FilterOption(sev, SEVERITY_LABELS.get(sev), n));
        }
        return options;
    }

    /** 접근성 문제의 지침 항목 (KWCAG 2.2 · WCAG 2.2). 접근성이 아니거나 모르는 규칙이면 null */
    public A11yGuideline.Item guideline(RuleGroup g) {
        return g != null && "ACCESSIBILITY".equals(g.category()) ? A11yGuideline.of(g.ruleId()) : null;
    }

    /** 발견된 곳의 '주소' 칸 이름: 종류에 맞게 */
    public String targetLabel(Finding f) {
        return switch (f.category() == null ? "" : f.category()) {
            case "LINK" -> "연결된 주소";
            case "IMAGE" -> "이미지 주소";
            case "RESOURCE" -> "파일 주소";
            case "API" -> "데이터 요청 주소";
            case "SECURITY" -> "보안 안 된 주소";
            case "PERFORMANCE" -> "원인 파일";
            case "SCRIPT" -> "오류가 난 파일";
            default -> "주소";
        };
    }

    /** 유형 순 정렬에 쓰는 분류 순서 번호 */
    public int catRank(String category) {
        return rank(CATEGORY_ORDER, category);
    }

    // ---------- 재검사 비교 ----------

    public boolean compared() {
        return comparison != null;
    }

    /** 이 항목이 지난 검사에 없던 새 문제인지 */
    public boolean isNew(Finding f) {
        return comparison != null && comparison.isNew(f);
    }

    /** 이 문제 묶음 중 새로 생긴 곳의 수 */
    public long newIn(RuleGroup g) {
        return comparison == null ? 0 : g.items().stream().filter(comparison::isNew).count();
    }

    /** 새로 생긴 문제가 있는 묶음 (팝업 목록용) */
    public List<RuleGroup> newGroups() {
        return comparison == null ? List.of() : groups.stream().filter(g -> newIn(g) > 0).toList();
    }

    /** 해결된 문제를 규칙별로 묶음 (지난 검사에만 있던 것) */
    public List<RuleGroup> resolvedGroups() {
        if (comparison == null) return List.of();
        Map<String, List<Finding>> grouped = new LinkedHashMap<>();
        for (Finding f : comparison.resolved()) {
            grouped.computeIfAbsent(f.source() + "|" + f.ruleId(), k -> new ArrayList<>()).add(f);
        }
        List<RuleGroup> result = new ArrayList<>();
        for (List<Finding> items : grouped.values()) {
            items.sort(Comparator.comparingInt(f -> rank(SEVERITY_ORDER, f.severity())));
            result.add(toGroup(items).withId("r" + (result.size() + 1)));
        }
        result.sort(Comparator.comparingInt((RuleGroup g) -> rank(SEVERITY_ORDER, g.severity()))
                .thenComparing(Comparator.comparingInt(RuleGroup::count).reversed()));
        return result;
    }

    /** 페이지별 점수표: 페이지마다 점수, 심각도별 건수, 그 페이지에서 나온 문제 종류 */
    private static List<PageRow> buildPages(ScanReport report, List<RuleGroup> groups) {
        if (report.pages() == null) return List.of();
        List<PageRow> rows = new ArrayList<>();
        int index = 0;
        for (ScanReport.PageResult p : report.pages()) {
            index++;
            String url = p.pageUrl();
            List<PageIssue> issues = new ArrayList<>();
            Map<String, Integer> bySeverity = new LinkedHashMap<>();
            SEVERITY_ORDER.forEach(s -> bySeverity.put(s, 0));
            for (RuleGroup g : groups) {
                List<Finding> here = g.items().stream().filter(f -> f.onPage(url)).toList();
                if (here.isEmpty()) continue;
                issues.add(new PageIssue(g.id(), g.severity(), g.severityLabel(), g.title(), g.manual(), here.size()));
                if (!g.manual()) {
                    for (Finding f : here) bySeverity.merge(f.severity(), 1, Integer::sum);
                }
            }
            boolean opened = QualityScore.opened(p);
            Integer score = QualityScore.pageScore(report, p);
            rows.add(new PageRow(index, url, shortPath(url), p.title(), p.httpStatus(), opened, score,
                    bySeverity, issues, mobileOverflow(p),
                    p.scores() == null ? null : p.scores().performance(), p.scores() == null ? null : p.scores().seo()));
        }
        return rows;
    }

    private static boolean mobileOverflow(ScanReport.PageResult p) {
        ScanReport.Mobile m = p.mobile();
        return m != null && m.scrollWidth() != null && m.viewportWidth() != null && m.scrollWidth() - m.viewportWidth() > 1;
    }

    /** 주소에서 도메인을 뺀 경로 ("/sub/notice.do?menu=3"), 길면 줄임 */
    static String shortPath(String url) {
        if (url == null) return "-";
        try {
            java.net.URI u = new java.net.URI(url);
            String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
                    + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            return path.length() > 70 ? path.substring(0, 70) + "…" : path;
        } catch (java.net.URISyntaxException e) {
            return url;
        }
    }

    public boolean multiPage() {
        return pages.size() > 1;
    }

    /** 화면에서 페이지 주소를 짧게 보여 줄 때 */
    public String pathOf(String url) {
        return shortPath(url);
    }

    /** 점수표 아래에 적는 점수 계산 방법 */
    public String scoreRule() {
        return QualityScore.RULE;
    }

    /** 이 검사의 품질 점수 (페이지 점수 평균) */
    public Integer qualityScore() {
        return QualityScore.scanScore(report);
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
    private static List<CheckRow> buildChecks(List<Finding> findings, ScanReport report) {
        ScanReport.PageResult page = report.firstPage();
        List<ScanReport.PageResult> all = report.pages() == null ? List.of() : report.pages();
        boolean multi = all.size() > 1;
        boolean pageLoaded = page != null && page.httpStatus() != null;
        boolean lighthouse = page != null && page.scores() != null;
        List<CheckRow> rows = new ArrayList<>();
        for (String[] item : CHECK_ITEMS) {
            String code = item[0];
            long count = findings.stream().filter(inCategory(code)).count();
            boolean measured = switch (code) {
                case "AVAILABILITY" -> true;
                case "MOBILE" -> all.stream().anyMatch(p -> p.mobile() != null);
                case "ESS_PRIVACY", "ESS_TERMS", "ESS_COPYRIGHT" -> all.stream().anyMatch(p -> p.essentials() != null);
                case "LINK" -> all.stream().anyMatch(p -> p.linksChecked() != null);
                case "PERFORMANCE", "SEO" -> lighthouse;
                // https 페이지에서만 의미가 있는 검사
                // 접근성: 접근성을 검사한 점검(종합 · 웹접근성)만, 예전 결과는 페이지가 열렸으면
                case "ACCESSIBILITY" -> pageLoaded && (checkType(report) == null || List.of("all", "a11y").contains(checkType(report)));
                case "SECURITY" -> checkType(report) == null
                        ? pageLoaded && page.pageUrl() != null && page.pageUrl().startsWith("https://")
                        : pageLoaded && List.of("all", "security").contains(checkType(report));
                default -> pageLoaded;
            };
            int images = all.stream().mapToInt(p -> p.imageCount() == null ? 0 : p.imageCount()).sum();
            boolean scrolled = all.stream().anyMatch(p -> p.scroll() != null);
            String note = switch (code) {
                case "AVAILABILITY" -> multi ? "페이지 " + all.size() + "개 확인" : null;
                case "LINK" -> multi
                        ? (report.scope() != null && report.scope().linksChecked() != null
                                ? "페이지 " + all.size() + "개 · 링크 " + report.scope().linksChecked() + "개 확인" : null)
                        : page == null || page.linksChecked() == null ? null
                        : page.linkCount() != null && page.linksChecked() < page.linkCount()
                                ? "링크 " + page.linkCount() + "개 중 " + page.linksChecked() + "개 확인"
                                : "링크 " + page.linksChecked() + "개 확인";
                // 끝까지 스크롤해 늦게 뜨는(lazy) 이미지까지 확인했으면 함께 알려 준다
                case "IMAGE" -> !pageLoaded ? null : "이미지 " + images + "개 확인" + (scrolled ? " · 끝까지 내려 봄" : "");
                case "MOBILE" -> multi ? "페이지 " + all.size() + "개 확인" : null;
                // 필수 요소: 찾은 문구를 근거로 보여 준다 (첫 페이지 기준)
                case "ESS_PRIVACY", "ESS_TERMS", "ESS_COPYRIGHT" -> multi && count > 0
                        ? "페이지 " + all.size() + "개 중 " + missingPages(findings, code) + "개에서 못 찾음"
                        : essentialNote(code, page);
                case "PERFORMANCE" -> lighthouseNote(report, true);
                case "SEO" -> lighthouseNote(report, false);
                case "SECURITY" -> certNote(page);
                default -> null;
            };
            String status = !measured ? ("SECURITY".equals(code) && pageLoaded ? "해당 없음" : "측정 안 함")
                    : count == 0 ? "이상 없음" : count + "건";
            rows.add(new CheckRow(code, item[1], item[2], count, measured, status, note));
        }
        return rows;
    }

    /** 이 검사의 점검 종류 (예전 결과는 null) */
    static String checkType(ScanReport report) {
        return report.scope() == null ? null : report.scope().check();
    }

    /** Lighthouse 점수 평균 (측정한 페이지가 없으면 null) */
    static Integer lighthouseAverage(ScanReport report, boolean performance) {
        if (report.pages() == null) return null;
        java.util.OptionalDouble avg = report.pages().stream()
                .map(p -> p.scores() == null ? null : performance ? p.scores().performance() : p.scores().seo())
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).average();
        return avg.isPresent() ? (int) Math.round(avg.getAsDouble()) : null;
    }

    /** "85점 / 100" 또는 여러 페이지면 "평균 85점 (페이지 12개)" */
    private static String lighthouseNote(ScanReport report, boolean performance) {
        Integer avg = lighthouseAverage(report, performance);
        if (avg == null) return null;
        long n = report.pages().stream()
                .filter(p -> p.scores() != null && (performance ? p.scores().performance() : p.scores().seo()) != null).count();
        return n > 1 ? "평균 " + avg + "점 (페이지 " + n + "개)" : avg + "점 / 100";
    }

    /** 보안 인증서 만료일 안내 */
    private static String certNote(ScanReport.PageResult page) {
        if (page == null) return null;
        if (page.cert() != null && page.cert().validTo() != null) {
            String until = page.cert().validTo().length() >= 10 ? page.cert().validTo().substring(0, 10) : page.cert().validTo();
            return "보안 인증서 " + until + "까지" + (page.cert().daysLeft() != null ? " (" + page.cert().daysLeft() + "일 남음)" : "");
        }
        return page.pageUrl() != null && page.pageUrl().startsWith("http://") ? "https를 쓰지 않는 사이트" : null;
    }

    /** 필수 요소를 못 찾은 페이지 수 (여러 페이지 문제는 1건 + pages[]) */
    private static long missingPages(List<Finding> findings, String code) {
        return findings.stream().filter(f -> code.equals(f.category()) && !f.manualCheck())
                .flatMap(f -> f.pageList().stream()).distinct().count();
    }

    /** 필수 요소에서 찾은 문구 → "찾은 문구: …" (첫 페이지에서 못 찾았으면 null) */
    private static String essentialNote(String code, ScanReport.PageResult page) {
        if (page == null || page.essentials() == null) return null;
        ScanReport.Essentials e = page.essentials();
        String found = switch (code) {
            case "ESS_PRIVACY" -> e.privacy();
            case "ESS_TERMS" -> e.terms();
            default -> e.copyright();
        };
        return found == null ? null : "찾은 문구: “" + found + "”";
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
        // SEO 점검처럼 로딩 속도를 재지 않았으면 지표 그래프 없음
        if (page == null || page.metricDetails() == null || page.scores() == null || page.scores().performance() == null) return List.of();
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
                        m.rating() == null ? "-" : RATING_LABELS.getOrDefault(m.rating(), "-"),
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

    /** 페이지 접속 상태: "정상" / "없는 페이지" / "서버 오류" / "접속 안 됨" (사이트 전체 검사면 "정상" / "N개 안 열림") */
    public String accessLabel() {
        if (multiPage()) {
            long failed = pages.stream().filter(p -> !p.opened()).count();
            return failed == 0 ? "정상" : failed + "개 안 열림";
        }
        Integer code = page == null ? null : page.httpStatus();
        if (code == null) return "접속 안 됨";
        if (code < 400) return "정상";
        if (code == 404) return "없는 페이지";
        if (code >= 500) return "서버 오류";
        return "열리지 않음";
    }

    public boolean accessOk() {
        if (multiPage()) return pages.stream().allMatch(PageRow::opened);
        return page != null && page.httpStatus() != null && page.httpStatus() < 400;
    }

    /** 휴대폰 화면 상태: "정상" / "옆으로 밀림" / "확인 못 함" (사이트 전체 검사면 "정상" / "N개 밀림") */
    public String mobileLabel() {
        if (multiPage()) {
            long bad = pages.stream().filter(PageRow::mobileOverflow).count();
            return bad == 0 ? "정상" : bad + "개 밀림";
        }
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

    /**
     * 페이지별 점수표의 한 줄
     * score: 열리지 않은 페이지는 null, bySeverity: 직접 확인 필요를 뺀 심각도별 건수, issues: 이 페이지에서 나온 문제 종류
     */
    public record PageRow(int index, String url, String path, String title, Integer httpStatus, boolean opened,
                          Integer score, Map<String, Integer> bySeverity, List<PageIssue> issues, boolean mobileOverflow,
                          Integer perfScore, Integer seoScore) {

        /** 점수 구간: 80점 이상 양호, 50~79 주의, 50 미만 위험 */
        public String grade() {
            if (score == null) return "bad";
            return score >= 80 ? "good" : score >= 50 ? "mid" : "bad";
        }

        public String gradeLabel() {
            if (score == null) return "열리지 않음";
            return score >= 80 ? "양호" : score >= 50 ? "주의" : "위험";
        }

        public int issueTotal() {
            return bySeverity.values().stream().mapToInt(Integer::intValue).sum();
        }

        public String displayTitle() {
            return title == null || title.isBlank() ? path : title;
        }
    }

    /** 페이지 한 곳에서 나온 문제 종류 (팝업에서 누르면 그 문제의 상세 팝업이 열림) */
    public record PageIssue(String groupId, String severity, String severityLabel, String title, boolean manual, int count) {
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

    // ---------- 영역별 결과 (기능 결함 · 접근성 · 로딩 속도 · SEO) ----------

    /**
     * 영역 하나: 번호, 코드, 이름, 무엇을 보는지, 세부 검사 항목, Lighthouse 점수(로딩 속도·SEO), 할 일, 문제 묶음
     */
    public record Area(int no, String code, String label, String what, List<CheckRow> checks, Integer score,
                       List<PlanItem> plan, List<RuleGroup> groups) {

        /** 발견된 문제 수 (직접 확인 필요 제외) */
        public long count() {
            return checks.stream().filter(CheckRow::measured).mapToLong(CheckRow::count).sum();
        }

        public boolean measured() {
            return checks.stream().anyMatch(CheckRow::measured);
        }

        /** 이상 없음 / N건 / 측정 안 함 */
        public String status() {
            if (!measured()) return "측정 안 함";
            return count() == 0 ? "이상 없음" : count() + "건";
        }

        /** 화면 색: bad(문제 있음) / ok(이상 없음) / skip(측정 안 함) */
        public String tone() {
            if (!measured()) return "skip";
            return count() > 0 ? "bad" : "ok";
        }

        /** 심각도별 곳 수 (보고서 그래프용) */
        public long sevCount(String severity) {
            return groups.stream().filter(g -> severity.equals(g.severity())).mapToLong(RuleGroup::count).sum();
        }

        /** 심각도별 곳 수의 비율(%) — 쌓은 막대 너비 */
        public String sevWidth(String severity) {
            long total = groups.stream().mapToLong(RuleGroup::count).sum();
            return total == 0 ? "width: 0%;" : "width: " + Math.round(sevCount(severity) * 1000.0 / total) / 10.0 + "%;";
        }

        /** 세부 항목이 영역 하나뿐인지 (접근성·로딩 속도·SEO) */
        public boolean single() {
            return checks.size() == 1;
        }

        /** 걸러 보기 값: 세부 항목이 여럿이면 영역 전체(AREA:코드), 하나면 그 분류 */
        public String filterCode() {
            return single() ? checks.get(0).code() : "AREA:" + code;
        }

        public long okCount() {
            return checks.stream().filter(c -> c.measured() && c.count() == 0).count();
        }

        public long badCount() {
            return checks.stream().filter(c -> c.measured() && c.count() > 0).count();
        }

        /** 보고서 원형 점수판의 채움 길이 (Lighthouse 점수) */
        public String ringDash() {
            return ReportCharts.dash(score);
        }

        /** Lighthouse 점수 구간 (good / mid / bad / none) */
        public String scoreGrade() {
            if (score == null) return "none";
            return score >= 90 ? "good" : score >= 50 ? "mid" : "bad";
        }
    }

    /** 영역 4가지 (번호 순) */
    public List<Area> areas() {
        List<Area> out = new ArrayList<>();
        boolean essentials = report.pages() != null && report.pages().stream().anyMatch(p -> p.essentials() != null);
        for (String[] a : AREA_ITEMS) {
            String code = a[0];
            if ("ESSENTIAL".equals(code) && !essentials) continue; // 일반 검사에서는 필수 요소 영역을 보이지 않음
            String only = AREA_BY_CHECK.get(checkType(report) == null ? "all" : checkType(report));
            if (only != null && !only.equals(code)) continue; // 개별 점검은 그 영역만
            Integer score = "PERFORMANCE".equals(code) ? lighthouseAverage(report, true)
                    : "SEO".equals(code) ? lighthouseAverage(report, false) : null;
            out.add(new Area(out.size() + 1, code, a[1], a[2],
                    checks.stream().filter(c -> code.equals(areaOf(c.code()))).toList(), score,
                    plan.stream().filter(p -> code.equals(areaOf(p.category()))).toList(),
                    groups.stream().filter(g -> !g.manual() && code.equals(areaOf(g.category()))).toList()));
        }
        return out;
    }

    // ---------- KWCAG 2.2 검사항목별 결과 (33개) ----------

    /**
     * 검사항목 한 줄
     * tone: bad(자동 검사에서 문제) / warn(자동 판단이 어려워 확인 필요한 곳이 나옴) / ok(자동 검사 이상 없음)
     *       / manual(자동 검사 대상이 아님 → 직접 확인) / skip(접근성을 측정하지 않음)
     */
    public record KwcagRow(Kwcag.Item item, boolean automated, long violations, long manual, String tone, String status) {

        /** 화면 걸러 보기 값 (K:번호) */
        public String filterCode() {
            return "K:" + item.code();
        }
    }

    public record KwcagPrinciple(int no, String name, List<KwcagRow> rows) {

        public long badCount() {
            return rows.stream().filter(r -> "bad".equals(r.tone())).count();
        }

        public long okCount() {
            return rows.stream().filter(r -> "ok".equals(r.tone())).count();
        }

        /** 직접 확인할 항목 수 (확인 필요 + 직접 확인) */
        public long checkCount() {
            return rows.stream().filter(r -> "warn".equals(r.tone()) || "manual".equals(r.tone())).count();
        }

        /** 원칙 안에서 tone 비율(%) — 쌓은 막대 너비 */
        public String width(String tone) {
            long n = switch (tone) {
                case "bad" -> badCount();
                case "ok" -> okCount();
                default -> checkCount();
            };
            return rows.isEmpty() ? "width: 0%;" : "width: " + Math.round(n * 1000.0 / rows.size()) / 10.0 + "%;";
        }
    }

    /** 원칙 4개 + 합계: 문제 있는 항목 / 자동 검사 이상 없음 / 직접 확인할 항목 */
    public record KwcagView(boolean measured, List<KwcagPrinciple> principles, long bad, long ok, long toCheck) {

        public int total() {
            return Kwcag.ITEMS.size();
        }
    }

    public KwcagView kwcag() {
        boolean measured = checks.stream().anyMatch(c -> "ACCESSIBILITY".equals(c.code()) && c.measured());
        java.util.Set<String> automated = A11yGuideline.automatedKwcagCodes();
        List<KwcagPrinciple> principles = new ArrayList<>();
        long bad = 0, ok = 0, toCheck = 0;
        for (String p : Kwcag.PRINCIPLES) {
            List<KwcagRow> rows = new ArrayList<>();
            for (Kwcag.Item it : Kwcag.ITEMS) {
                if (!p.equals(it.principle())) continue;
                long v = kwcagCount(it.code(), false);
                long m = kwcagCount(it.code(), true);
                boolean auto = automated.contains(it.code());
                String tone;
                String status;
                if (!measured) { tone = "skip"; status = "측정 안 함"; }
                else if (v > 0) { tone = "bad"; status = "문제 " + v + "건"; bad++; }
                else if (m > 0) { tone = "warn"; status = "확인 필요 " + m + "곳"; toCheck++; }
                else if (auto) { tone = "ok"; status = "자동 검사 이상 없음"; ok++; }
                else { tone = "manual"; status = "직접 확인"; toCheck++; }
                rows.add(new KwcagRow(it, auto, v, m, tone, status));
            }
            principles.add(new KwcagPrinciple(principles.size() + 1, p, rows));
        }
        return new KwcagView(measured, principles, bad, ok, toCheck);
    }

    /** 이 검사항목에 해당하는 접근성 문제 곳 수 (manual: 직접 확인 필요 항목만 / 아니면 확정된 문제만) */
    private long kwcagCount(String code, boolean manual) {
        return groups.stream()
                .filter(g -> g.manual() == manual && "ACCESSIBILITY".equals(g.category()))
                .filter(g -> {
                    A11yGuideline.Item gl = A11yGuideline.of(g.ruleId());
                    return gl != null && code.equals(gl.kwcagCode());
                })
                .mapToLong(RuleGroup::count).sum();
    }

    /** 화면 걸러 보기용: 개선 방안 한 줄(묶음 번호)의 KWCAG 번호 */
    public String kwcagCodeOf(String groupId) {
        return groups.stream().filter(g -> g.id().equals(groupId)).findFirst().map(this::kwcagCode).orElse("");
    }

    /** 화면 걸러 보기용: 접근성 문제의 KWCAG 번호 (아니면 빈 값) */
    public String kwcagCode(RuleGroup g) {
        A11yGuideline.Item gl = guideline(g);
        return gl == null || gl.kwcagCode() == null ? "" : gl.kwcagCode();
    }

    /** 영역 하나 (없으면 null) */
    public Area area(String code) {
        return areas().stream().filter(a -> a.code().equals(code)).findFirst().orElse(null);
    }

    /** 접근성의 '직접 확인 필요' 묶음 (자동 검사가 판단을 보류한 곳) */
    public List<RuleGroup> a11yManualGroups() {
        return groups.stream().filter(g -> g.manual() && "ACCESSIBILITY".equals(g.category())).toList();
    }

    /** 화면 걸러 보기용: 분류가 속한 영역 코드 */
    public String areaCode(String category) {
        return areaOf(category);
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

        /** 실제로 발견된 횟수 합계 (같은 자리 반복 포함). count()보다 크면 "같은 자리 반복을 1건으로 셈" 안내 */
        public int occurrenceTotal() {
            return items.stream().mapToInt(Finding::occurrenceCount).sum();
        }

        /** 이 문제가 나온 페이지 수 (사이트 전체 검사에서 "3개 페이지" 표시용) */
        public long pageCount() {
            return items.stream().flatMap(f -> f.pageList().stream()).distinct().count();
        }

        /** 이 문제 중 위치 캡처가 있는 곳의 수 */
        public long shotCount() {
            return items.stream().filter(f -> f.screenshotName() != null).count();
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
