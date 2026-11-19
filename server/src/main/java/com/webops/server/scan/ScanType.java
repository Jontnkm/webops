package com.webops.server.scan;

import java.util.Locale;

/**
 * 점검 종류 7가지. 종합점검만 검사 범위(메인 / 현재 페이지 / 웹사이트 전체)를 고르고,
 * 나머지 점검은 늘 웹사이트 전체 페이지를 본다. cli는 실행기 --check 값.
 */
public enum ScanType {
    ALL("all", "종합점검", "웹사이트를 선택한 범위에 맞게 종합점검합니다."),
    FUNCTION("function", "기능점검", "웹사이트의 기능을 위주로 점검합니다."),
    A11Y("a11y", "웹접근성 점검", "웹사이트의 웹접근성 준수 여부를 점검합니다."),
    PERF("perf", "로딩속도 점검", "웹사이트 전반의 로딩속도를 점검합니다."),
    SEO("seo", "SEO 점검", "웹사이트의 마케팅을 위한 SEO 설정에 대해 점검합니다."),
    SECURITY("security", "보안점검", "웹사이트 전반의 보안을 점검합니다."),
    MOBILE("mobile", "모바일환경 점검", "웹사이트의 모바일환경을 점검합니다. 단, 반응형이 아닐 경우 모바일 주소를 별도 입력해 주세요.");

    private final String cli;
    private final String label;
    private final String description;

    ScanType(String cli, String label, String description) {
        this.cli = cli;
        this.label = label;
        this.description = description;
    }

    public String getCli() { return cli; }
    public String getLabel() { return label; }
    public String getDescription() { return description; }

    /** 로딩속도 · SEO 점검: 모든 페이지를 Lighthouse로 측정 (오래 걸림) */
    public boolean isLighthouseAll() {
        return this == PERF || this == SEO;
    }

    /** 화면 값·DB 값 → 점검 종류 (모르거나 비어 있으면 종합점검) */
    public static ScanType from(String value) {
        if (value == null || value.isBlank()) return ALL;
        String v = value.trim();
        for (ScanType t : values()) {
            if (t.name().equalsIgnoreCase(v) || t.cli.equalsIgnoreCase(v)) return t;
        }
        return ALL;
    }

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
