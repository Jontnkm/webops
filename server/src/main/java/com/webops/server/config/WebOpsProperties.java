package com.webops.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * application.properties의 webops.* 설정값.
 */
@ConfigurationProperties(prefix = "webops")
public record WebOpsProperties(@DefaultValue Runner runner, @DefaultValue Scan scan, @DefaultValue Alert alert) {

    /**
     * 문제 발생 알림 메일
     * from: 보내는 사람 주소, baseUrl: 메일 안 '결과 보기' 링크의 앞부분 (다른 PC에서 열 수 있는 주소로 바꿔야 함)
     * 메일 서버(SMTP) 자체는 spring.mail.host / port / username / password로 설정한다.
     */
    public record Alert(
            @DefaultValue("webops@localhost") String from,
            @DefaultValue("http://127.0.0.1:8080") String baseUrl) {
    }

    /**
     * 검사 실행기(Node.js runner) 실행 설정
     * timeoutSeconds: 페이지 1개 검사 제한 시간, perPageSeconds: 웹사이트 전체 점검에서 페이지가 하나 늘 때마다 더 주는 시간
     * siteConcurrency: 웹사이트 전체 점검 때 동시에 여는 탭 수 (1~5)
     */
    public record Runner(
            @DefaultValue("../runner") String dir,
            @DefaultValue("node") String node,
            @DefaultValue("2") int maxConcurrent,
            @DefaultValue("180") int timeoutSeconds,
            @DefaultValue("45") int perPageSeconds,
            @DefaultValue("90") int pdfTimeoutSeconds,
            @DefaultValue("3") int siteConcurrency) {
    }

    /** 검사 대상 주소 정책, 사이트 전체 검사 최대 페이지 수 */
    public record Scan(@DefaultValue("false") boolean allowPrivateTargets, @DefaultValue("300") int maxPages) {
    }
}
