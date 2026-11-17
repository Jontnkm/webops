package com.webops.server.site;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정기 검사 시계: 30초마다 '검사할 때가 된 사이트'가 있는지 보고 검사를 시작한다.
 * 서버가 꺼져 있는 동안 지나간 검사는 서버를 켠 뒤 한 번 실행하고 다음 일정으로 넘어간다.
 */
@Component
public class SiteScheduler {

    private static final Logger log = LoggerFactory.getLogger(SiteScheduler.class);

    private final SiteService siteService;

    public SiteScheduler(SiteService siteService) {
        this.siteService = siteService;
    }

    @Scheduled(initialDelayString = "${webops.schedule.initial-delay-ms:10000}",
               fixedDelayString = "${webops.schedule.check-interval-ms:30000}")
    public void tick() {
        try {
            siteService.runDue();
        } catch (RuntimeException e) {
            log.warn("정기 검사 확인 중 오류", e);
        }
    }
}
