package com.webops.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * application.properties의 webops.* 설정값.
 */
@ConfigurationProperties(prefix = "webops")
public record WebOpsProperties(@DefaultValue Runner runner, @DefaultValue Scan scan) {

    /** 검사 실행기(Node.js runner) 실행 설정 */
    public record Runner(
            @DefaultValue("../runner") String dir,
            @DefaultValue("node") String node,
            @DefaultValue("2") int maxConcurrent,
            @DefaultValue("180") int timeoutSeconds) {
    }

    /** 검사 대상 주소 정책 */
    public record Scan(@DefaultValue("false") boolean allowPrivateTargets) {
    }
}
