package com.webops.server.scan;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 검사 범위
 *   MAIN  메인 페이지만      어떤 주소를 넣어도 그 사이트의 첫 화면(https://도메인/)만 검사
 *   PAGE  입력한 현재 페이지  넣은 주소 그 페이지만 검사
 *   SITE  웹사이트 전체 점검  같은 사이트 링크를 끝까지 따라가며 검사 (안전 상한: webops.scan.max-pages)
 * DB에는 따로 저장하지 않고 주소와 최대 페이지 수로 알아낸다 (of): 여러 페이지면 SITE, 주소가 첫 화면이면 MAIN, 아니면 PAGE.
 */
public enum ScanScope {
    MAIN("메인 페이지만", "어떤 주소를 넣어도 그 사이트의 첫 화면(메인)만 검사합니다"),
    PAGE("입력한 현재 페이지", "넣은 주소의 그 페이지만 검사합니다"),
    SITE("웹사이트 전체 점검", "같은 사이트 링크를 끝까지 따라가며 모든 페이지를 검사합니다");

    private final String label;
    private final String description;

    ScanScope(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() { return label; }
    public String getDescription() { return description; }
    public String getCode() { return name(); }

    /** 화면에서 받은 값 → 범위 (모르는 값이면 MAIN) */
    public static ScanScope from(String code) {
        if (code != null) {
            for (ScanScope s : values()) {
                if (s.name().equalsIgnoreCase(code.strip())) return s;
            }
        }
        return MAIN;
    }

    /** 검사할 주소: 메인 페이지만이면 경로를 버리고 사이트 첫 화면으로 */
    public String targetUrl(String checkedUrl) {
        if (this != MAIN) return checkedUrl;
        try {
            URI u = new URI(checkedUrl);
            return new URI(u.getScheme(), null, u.getHost(), u.getPort(), "/", null, null).toString();
        } catch (URISyntaxException e) {
            return checkedUrl;
        }
    }

    /** 실행기에 넘길 최대 페이지 수 (전체 점검은 안전 상한, 나머지는 1) */
    public int maxPages(int siteLimit) {
        return this == SITE ? Math.max(2, siteLimit) : 1;
    }

    /** 저장된 주소 · 최대 페이지 수로 범위를 알아낸다 */
    public static ScanScope of(String url, int maxPages) {
        if (maxPages > 1) return SITE;
        try {
            String path = new URI(url).getRawPath();
            String query = new URI(url).getRawQuery();
            return (path == null || path.isEmpty() || "/".equals(path)) && query == null ? MAIN : PAGE;
        } catch (URISyntaxException | NullPointerException e) {
            return PAGE;
        }
    }
}
