package com.webops.server.alert;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import com.webops.server.config.WebOpsProperties;
import com.webops.server.scan.ScanFinishedEvent;
import com.webops.server.scan.ScanJob;
import com.webops.server.scan.ScanStatus;
import com.webops.server.store.AlertLogEntity;
import com.webops.server.store.AlertLogRepository;
import com.webops.server.store.FindingEntity;
import com.webops.server.store.ScanJobEntity;
import com.webops.server.store.ScanStore;
import com.webops.server.store.SiteEntity;
import com.webops.server.store.SiteRepository;

/**
 * 문제 발생 알림: 정기 검사 대상 사이트의 검사가 끝나면 바로 전 검사와 견줘 메일을 보낸다.
 *   SITE_DOWN        첫 페이지가 열리지 않음 (접속 불가 · 오류 응답)
 *   RECOVERED        지난번에 열리지 않던 사이트가 다시 열림
 *   NEW_BROKEN_LINK  지난 검사에 없던 깨진 링크가 생김
 *   SCAN_FAILED      검사 자체를 끝내지 못함
 * 메일 서버(spring.mail.host)가 설정되지 않았으면 보내지 않고 기록만 남긴다 (SKIPPED).
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_LINKS_IN_MAIL = 20;

    private final SiteRepository sites;
    private final AlertLogRepository alertLogs;
    private final ScanStore store;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final WebOpsProperties.Alert notify;

    public AlertService(SiteRepository sites, AlertLogRepository alertLogs, ScanStore store,
                        ObjectProvider<JavaMailSender> mailSender, WebOpsProperties properties) {
        this.sites = sites;
        this.alertLogs = alertLogs;
        this.store = store;
        this.mailSender = mailSender;
        this.notify = properties.alert();
    }

    @EventListener
    public void onScanFinished(ScanFinishedEvent event) {
        ScanJob job = event.job();
        if (job.getSiteId() == null) return; // 직접 검사는 알림 대상이 아님
        Optional<SiteEntity> site = sites.findById(job.getSiteId());
        if (site.isEmpty() || site.get().getNotifyEmail() == null) return;

        List<String> types = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        if (job.getStatus() == ScanStatus.FAILED) {
            types.add("SCAN_FAILED");
            lines.add("■ 검사를 끝내지 못했습니다");
            lines.add("  " + job.getError());
        } else {
            judge(job, types, lines);
        }
        if (types.isEmpty()) return;
        send(site.get(), job, types, lines);
    }

    /** 바로 전 끝난 검사와 견줘 알릴 일을 찾는다 */
    private void judge(ScanJob job, List<String> types, List<String> lines) {
        ScanJobEntity current = store.find(job.getId()).orElse(null);
        if (current == null) return;
        ScanJobEntity previous = previousDone(current);

        if (current.isSiteDown()) {
            types.add("SITE_DOWN");
            lines.add("■ 사이트에 접속할 수 없습니다");
            lines.add("  " + current.getUrl() + " 페이지가 열리지 않습니다. 서버 상태를 확인해 주세요.");
            return; // 열리지 않으면 링크 비교는 의미가 없음
        }
        if (previous != null && previous.isSiteDown()) {
            types.add("RECOVERED");
            lines.add("■ 사이트가 다시 열립니다");
            lines.add("  지난 검사(" + previous.getCreatedAt().format(TIME) + ")에서는 열리지 않았던 사이트가 정상으로 열립니다.");
        }
        // 첫 검사거나 지난번에 열리지 않았으면, 지금 있는 깨진 링크를 '새로 생김'으로 볼 수 없다
        if (previous == null || previous.isSiteDown()) return;

        Set<String> before = new HashSet<>();
        for (FindingEntity f : store.findingsOf(previous.getJobId())) before.add(f.getFingerprint());
        List<FindingEntity> newBroken = store.findingsOf(current.getJobId()).stream()
                .filter(f -> "link-broken".equals(f.getRuleId()) && !before.contains(f.getFingerprint()))
                .toList();
        if (newBroken.isEmpty()) return;

        types.add("NEW_BROKEN_LINK");
        lines.add("■ 깨진 링크가 새로 " + newBroken.size() + "개 생겼습니다");
        for (FindingEntity f : newBroken.subList(0, Math.min(MAX_LINKS_IN_MAIL, newBroken.size()))) {
            lines.add("  - " + f.getTarget() + "  (" + f.getDescription() + ")");
            lines.add("    있는 곳: " + f.getPageUrl()
                    + (f.getPageCount() > 1 ? " 외 " + (f.getPageCount() - 1) + "개 페이지 (여러 페이지에 공통으로 있는 부분)" : ""));
        }
        if (newBroken.size() > MAX_LINKS_IN_MAIL) {
            lines.add("  … 외 " + (newBroken.size() - MAX_LINKS_IN_MAIL) + "개는 결과 화면에서 확인해 주세요.");
        }
    }

    private ScanJobEntity previousDone(ScanJobEntity current) {
        ScanJobEntity prev = null;
        for (ScanJobEntity j : store.historyOf(current.getSiteId())) {
            if (!j.getCreatedAt().isBefore(current.getCreatedAt())) break;
            if (ScanStore.DONE.equals(j.getStatus())) prev = j;
        }
        return prev;
    }

    private void send(SiteEntity site, ScanJob job, List<String> types, List<String> lines) {
        String headline = types.contains("SITE_DOWN") ? "접속 불가"
                : types.contains("SCAN_FAILED") ? "검사 실패"
                : types.contains("NEW_BROKEN_LINK") ? "깨진 링크 새로 생김"
                : "접속 복구";
        String subject = "[WebOps] " + site.getName() + " - " + headline;
        StringBuilder body = new StringBuilder();
        body.append(site.getName()).append(" (").append(site.getBaseUrl()).append(")\n");
        body.append("검사 시각: ").append(job.getCreatedAt().format(TIME))
                .append(job.isScheduled() ? " (정기 검사)" : " (직접 검사)").append("\n\n");
        for (String line : lines) body.append(line).append('\n');
        body.append("\n결과 보기: ").append(notify.baseUrl()).append("/scans/").append(job.getId()).append('\n');
        body.append("사이트 품질 추이: ").append(notify.baseUrl()).append("/sites/").append(site.getSiteId()).append('\n');
        body.append("\n이 메일은 WebOps(가칭) 정기 검사에서 자동으로 보냈습니다.\n");

        String recipients = site.getNotifyEmail();
        String status;
        String error = null;
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            status = "SKIPPED";
            log.info("알림 메일 서버 설정이 없어 보내지 않음: {}", subject);
        } else {
            try {
                SimpleMailMessage msg = new SimpleMailMessage();
                msg.setFrom(notify.from());
                msg.setTo(recipients.split(",\\s*"));
                msg.setSubject(subject);
                msg.setText(body.toString());
                sender.send(msg);
                status = "SENT";
                log.info("알림 메일 보냄: {} → {}", subject, recipients);
            } catch (RuntimeException e) {
                status = "FAILED";
                error = e.getMessage();
                log.warn("알림 메일 보내기 실패: {}", subject, e);
            }
        }
        alertLogs.save(new AlertLogEntity(site.getSiteId(), job.getId(), String.join(",", types), subject,
                body.toString(), recipients, status, error, LocalDateTime.now()));
    }
}
