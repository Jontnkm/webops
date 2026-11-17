package com.webops.server.web;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import com.webops.server.scan.ScanJob;

/**
 * 첫 화면 '최근 검사' 카드 한 장.
 * 구글 프로필처럼 동그라미 안에 사이트 주소의 첫 알파벳을 넣고, 배경색은 주소마다 정해진 색을 쓴다
 * (무작위처럼 보이지만 같은 사이트는 늘 같은 색 → 카드가 많아도 같은 사이트를 한눈에 알아봄).
 */
public record RecentCard(ScanJob job, String host, String initial, String color) {

    /** 흰 글자와 대비가 충분한 진한 색들 (큰 굵은 글자 기준 3:1 이상, 대부분 4.5:1 이상) */
    private static final List<String> COLORS = List.of(
            "#1565c0", "#2e7d32", "#c62828", "#6a1b9a", "#ad1457", "#00695c",
            "#bf360c", "#4527a0", "#283593", "#006064", "#5d4037", "#37474f");

    public static RecentCard of(ScanJob job) {
        String host = hostOf(job.getUrl());
        return new RecentCard(job, host, initialOf(host), COLORS.get(Math.floorMod(host.hashCode(), COLORS.size())));
    }

    /** "https://www.mois.go.kr/main" → "mois.go.kr" (www.는 뺌) */
    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return url;
            return host.toLowerCase(Locale.ROOT).replaceFirst("^www\\d*\\.", "");
        } catch (IllegalArgumentException e) {
            return url == null ? "" : url;
        }
    }

    /** 주소의 첫 알파벳 (대문자). 알파벳이 없으면(IP 주소 등) 첫 글자 */
    static String initialOf(String host) {
        for (char c : host.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) return String.valueOf(Character.toUpperCase(c));
        }
        return host.isEmpty() ? "?" : host.substring(0, 1);
    }

    /** 주소에서 도메인 뒤 경로 ("/sub/main.do"), 없으면 빈 문자열 */
    public String path() {
        try {
            URI u = URI.create(job.getUrl());
            String p = (u.getRawPath() == null ? "" : u.getRawPath()) + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            return "/".equals(p) ? "" : p;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** 점수 구간 색: 80점 이상 양호, 50~79 주의, 50 미만 위험 */
    public String grade() {
        Integer s = job.getQualityScore();
        if (s == null) return "none";
        return s >= 80 ? "good" : s >= 50 ? "mid" : "bad";
    }
}
