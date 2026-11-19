package com.webops.server.web;

import java.util.Map;

/**
 * 접근성 검사 규칙(axe-core) → 한국형 웹 콘텐츠 접근성 지침(KWCAG) 2.2 검사항목 · 국제 기준 WCAG 2.2 성공 기준.
 * 자동 검사 규칙을 지침 항목에 대응시킨 것이며, 인증 심사의 판정과 다를 수 있다 (화면에도 밝힌다).
 * KWCAG 항목이 따로 없는 규칙(WCAG에만 있는 기준)은 kwcagCode가 null.
 */
public final class A11yGuideline {

    /**
     * @param kwcagCode KWCAG 2.2 검사항목 번호 (예: 1.1.1), 해당 항목이 없으면 null
     * @param kwcagName 검사항목 이름 (예: 적절한 대체 텍스트 제공)
     * @param principle 원칙 (인식의 용이성 / 운용의 용이성 / 이해의 용이성 / 견고성)
     * @param wcagCode  WCAG 2.2 성공 기준 번호 (예: 1.1.1)
     * @param wcagName  성공 기준 이름 (우리말)
     * @param level     WCAG 등급 (A / AA)
     */
    public record Item(String kwcagCode, String kwcagName, String principle, String wcagCode, String wcagName, String level) {

        public boolean hasKwcag() {
            return kwcagCode != null;
        }
    }

    private static final String P1 = "인식의 용이성";
    private static final String P2 = "운용의 용이성";
    private static final String P3 = "이해의 용이성";
    private static final String P4 = "견고성";

    private static Item k(String code, String name, String principle, String wcag, String wcagName, String level) {
        return new Item(code, name, principle, wcag, wcagName, level);
    }

    private static final Item ALT = k("1.1.1", "적절한 대체 텍스트 제공", P1, "1.1.1", "텍스트 아닌 콘텐츠", "A");
    private static final Item CAPTION = k("1.2.1", "자막 제공", P1, "1.2.2", "자막(녹화된 콘텐츠)", "A");
    private static final Item TABLE = k("1.3.1", "표의 구성", P1, "1.3.1", "정보와 관계", "A");
    private static final Item COLOR = k("1.4.1", "색에 무관한 콘텐츠 인식", P1, "1.4.1", "색 사용", "A");
    private static final Item AUTOPLAY = k("1.4.2", "자동 재생 금지", P1, "1.4.2", "오디오 제어", "A");
    private static final Item CONTRAST = k("1.4.3", "텍스트 콘텐츠의 명도 대비", P1, "1.4.3", "명도 대비(최소)", "AA");
    private static final Item KEYBOARD = k("2.1.1", "키보드 사용 보장", P2, "2.1.1", "키보드", "A");
    private static final Item TARGET = k("2.1.3", "조작 가능", P2, "2.5.8", "누르는 대상 크기(최소)", "AA");
    private static final Item TIMING = k("2.2.1", "응답시간 조절", P2, "2.2.1", "시간 조정 가능", "A");
    private static final Item PAUSE = k("2.2.2", "정지 기능 제공", P2, "2.2.2", "일시 정지 · 정지 · 숨기기", "A");
    private static final Item BYPASS = k("2.4.1", "반복 영역 건너뛰기", P2, "2.4.1", "블록 건너뛰기", "A");
    private static final Item TITLE = k("2.4.2", "제목 제공", P2, "2.4.2", "페이지 제목", "A");
    private static final Item FRAME_TITLE = k("2.4.2", "제목 제공", P2, "4.1.2", "이름 · 역할 · 값", "A");
    private static final Item LINK = k("2.4.3", "적절한 링크 텍스트", P2, "2.4.4", "링크 목적(맥락 안에서)", "A");
    private static final Item LABEL_NAME = k("2.5.3", "레이블과 네임", P2, "2.5.3", "이름 안의 레이블", "A");
    private static final Item LANG = k("3.1.1", "기본 언어 표시", P3, "3.1.1", "페이지 언어", "A");
    private static final Item LANG_PART = k("3.1.1", "기본 언어 표시", P3, "3.1.2", "부분 언어", "AA");
    private static final Item FORM_LABEL = k("3.3.2", "레이블 제공", P3, "1.3.1 · 4.1.2", "정보와 관계 · 이름 · 역할 · 값", "A");
    private static final Item MARKUP = k("4.1.1", "마크업 오류 방지", P4, "1.3.1", "정보와 관계", "A");
    private static final Item ARIA = k("4.2.1", "웹 애플리케이션 접근성 준수", P4, "4.1.2", "이름 · 역할 · 값", "A");
    private static final Item ARIA_STRUCT = k("4.2.1", "웹 애플리케이션 접근성 준수", P4, "1.3.1", "정보와 관계", "A");

    /** axe-core 규칙 이름 → 지침 항목 */
    private static final Map<String, Item> BY_RULE = Map.ofEntries(
            Map.entry("image-alt", ALT),
            Map.entry("input-image-alt", ALT),
            Map.entry("area-alt", ALT),
            Map.entry("object-alt", ALT),
            Map.entry("svg-img-alt", ALT),
            Map.entry("role-img-alt", ALT),
            Map.entry("server-side-image-map", ALT),
            Map.entry("video-caption", CAPTION),
            Map.entry("td-headers-attr", TABLE),
            Map.entry("th-has-data-cells", TABLE),
            Map.entry("td-has-header", TABLE),
            Map.entry("table-fake-caption", TABLE),
            Map.entry("scope-attr-valid", TABLE),
            Map.entry("table-duplicate-name", TABLE),
            Map.entry("link-in-text-block", COLOR),
            Map.entry("no-autoplay-audio", AUTOPLAY),
            Map.entry("color-contrast", CONTRAST),
            Map.entry("scrollable-region-focusable", KEYBOARD),
            Map.entry("frame-focusable-content", KEYBOARD),
            Map.entry("target-size", TARGET),
            Map.entry("meta-refresh", TIMING),
            Map.entry("blink", PAUSE),
            Map.entry("marquee", PAUSE),
            Map.entry("bypass", BYPASS),
            Map.entry("document-title", TITLE),
            Map.entry("frame-title", FRAME_TITLE),
            Map.entry("frame-title-unique", FRAME_TITLE),
            Map.entry("link-name", LINK),
            Map.entry("label-content-name-mismatch", LABEL_NAME),
            Map.entry("html-has-lang", LANG),
            Map.entry("html-lang-valid", LANG),
            Map.entry("html-xml-lang-mismatch", LANG),
            Map.entry("valid-lang", LANG_PART),
            Map.entry("label", FORM_LABEL),
            Map.entry("select-name", FORM_LABEL),
            Map.entry("form-field-multiple-labels", FORM_LABEL),
            Map.entry("list", MARKUP),
            Map.entry("listitem", MARKUP),
            Map.entry("definition-list", MARKUP),
            Map.entry("dlitem", MARKUP),
            Map.entry("duplicate-id", MARKUP),
            Map.entry("duplicate-id-aria", MARKUP),
            Map.entry("duplicate-id-active", MARKUP),
            Map.entry("button-name", ARIA),
            Map.entry("input-button-name", ARIA),
            Map.entry("nested-interactive", ARIA),
            Map.entry("aria-allowed-attr", ARIA),
            Map.entry("aria-command-name", ARIA),
            Map.entry("aria-hidden-body", ARIA),
            Map.entry("aria-hidden-focus", ARIA),
            Map.entry("aria-input-field-name", ARIA),
            Map.entry("aria-meter-name", ARIA),
            Map.entry("aria-progressbar-name", ARIA),
            Map.entry("aria-prohibited-attr", ARIA),
            Map.entry("aria-required-attr", ARIA),
            Map.entry("aria-roles", ARIA),
            Map.entry("aria-toggle-field-name", ARIA),
            Map.entry("aria-tooltip-name", ARIA),
            Map.entry("aria-valid-attr", ARIA),
            Map.entry("aria-valid-attr-value", ARIA),
            Map.entry("aria-deprecated-role", ARIA),
            Map.entry("aria-braille-equivalent", ARIA),
            Map.entry("aria-conditional-attr", ARIA),
            Map.entry("aria-required-children", ARIA_STRUCT),
            Map.entry("aria-required-parent", ARIA_STRUCT),
            // KWCAG에 따로 대응하는 항목이 없고 WCAG에만 있는 기준
            Map.entry("meta-viewport", k(null, null, null, "1.4.4", "텍스트 크기 조정", "AA")),
            Map.entry("autocomplete-valid", k(null, null, null, "1.3.5", "입력 목적 식별", "AA")),
            Map.entry("avoid-inline-spacing", k(null, null, null, "1.4.12", "텍스트 간격", "AA")));

    private A11yGuideline() {
    }

    /** 자동 검사 규칙이 하나라도 대응되는 KWCAG 검사항목 번호들 */
    public static java.util.Set<String> automatedKwcagCodes() {
        java.util.Set<String> codes = new java.util.TreeSet<>();
        for (Item it : BY_RULE.values()) if (it.kwcagCode() != null) codes.add(it.kwcagCode());
        return codes;
    }

    /** 접근성 규칙의 지침 항목. 모르는 규칙이면 null */
    public static Item of(String ruleId) {
        return ruleId == null ? null : BY_RULE.get(ruleId);
    }
}
