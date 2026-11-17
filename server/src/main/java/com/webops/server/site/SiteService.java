package com.webops.server.site;

import java.net.URI;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.webops.server.config.WebOpsProperties;
import com.webops.server.scan.InvalidTargetException;
import com.webops.server.scan.ScanJob;
import com.webops.server.scan.ScanScope;
import com.webops.server.scan.ScanService;
import com.webops.server.scan.UrlGuard;
import com.webops.server.store.AlertLogEntity;
import com.webops.server.store.AlertLogRepository;
import com.webops.server.store.ScanJobRepository;
import com.webops.server.store.SiteEntity;
import com.webops.server.store.SiteRepository;

/**
 * 정기 검사 대상 사이트: 등록 · 수정 · 삭제 · 지금 검사 · 때가 된 정기 검사 실행.
 */
@Service
public class SiteService {

    private static final Logger log = LoggerFactory.getLogger(SiteService.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+$");

    private final SiteRepository sites;
    private final ScanJobRepository jobs;
    private final AlertLogRepository alerts;
    private final ScanService scanService;
    private final UrlGuard urlGuard;
    private final int maxPages;

    public SiteService(SiteRepository sites, ScanJobRepository jobs, AlertLogRepository alerts, ScanService scanService,
                       UrlGuard urlGuard, WebOpsProperties properties) {
        this.sites = sites;
        this.jobs = jobs;
        this.alerts = alerts;
        this.scanService = scanService;
        this.urlGuard = urlGuard;
        this.maxPages = Math.max(1, properties.scan().maxPages());
    }

    /** 등록·수정 화면에서 받는 값 */
    /** scope: MAIN(메인 페이지만) / PAGE(입력한 현재 페이지) / SITE(웹사이트 전체 점검) */
    public record SiteForm(String name, String url, String scope, boolean allPosts, boolean lighthouse, String schedule, int hour, int dow,
                           String email, boolean runNow) {
    }

    @Transactional(readOnly = true)
    public List<SiteEntity> list() {
        return sites.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Optional<SiteEntity> find(Long id) {
        return sites.findById(id);
    }

    @Transactional(readOnly = true)
    public List<AlertLogEntity> alertsOf(Long siteId, int limit) {
        return alerts.findBySiteIdOrderByCreatedAtDesc(siteId, PageRequest.of(0, limit));
    }

    @Transactional
    public SiteEntity create(SiteForm form) {
        String url = ScanScope.from(form.scope()).targetUrl(urlGuard.check(form.url()));
        SiteEntity site = new SiteEntity(nameOr(form.name(), url), url, LocalDateTime.now());
        apply(site, form);
        // '등록하고 바로 첫 검사': 기준이 되는 첫 기록을 남겨 둬야 다음 검사부터 비교·추이가 나온다
        if (form.runNow()) site.setNextRunAt(LocalDateTime.now());
        return sites.save(site);
    }

    @Transactional
    public SiteEntity update(Long id, SiteForm form) {
        SiteEntity site = sites.findById(id).orElseThrow(() -> new InvalidTargetException("사이트를 찾을 수 없습니다."));
        String url = ScanScope.from(form.scope()).targetUrl(urlGuard.check(form.url()));
        site.setBaseUrl(url);
        site.setName(nameOr(form.name(), url));
        apply(site, form);
        return site;
    }

    private void apply(SiteEntity site, SiteForm form) {
        String schedule = switch (form.schedule() == null ? "" : form.schedule()) {
            case "DAILY", "WEEKLY" -> form.schedule();
            default -> "NONE";
        };
        site.setMaxPages(ScanScope.from(form.scope()).maxPages(maxPages));
        site.setAllPosts(form.allPosts());
        site.setLighthouse(form.lighthouse());
        site.setScheduleType(schedule);
        site.setScheduleHour(Math.min(Math.max(0, form.hour()), 23));
        site.setScheduleDow(Math.min(Math.max(1, form.dow()), 7));
        site.setNotifyEmail(normalizeEmails(form.email()));
        site.setNextRunAt(nextRun(site, LocalDateTime.now()));
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        sites.findById(id).ifPresent(site -> {
            site.setEnabled(enabled);
            site.setNextRunAt(enabled ? nextRun(site, LocalDateTime.now()) : null);
        });
    }

    /** 사이트를 지운다. 지난 검사 기록과 알림 기록은 남긴다 (사이트 연결만 끊음). */
    @Transactional
    public void delete(Long id) {
        jobs.detachSite(id);
        alerts.detachSite(id);
        sites.deleteById(id);
    }

    /** '지금 검사' 버튼 */
    @Transactional
    public ScanJob runNow(Long id) {
        SiteEntity site = sites.findById(id).orElseThrow(() -> new InvalidTargetException("사이트를 찾을 수 없습니다."));
        String url = urlGuard.check(site.getBaseUrl());
        return scanService.start(url, site.isLighthouse(), site.getMaxPages(), site.isAllPosts(), site.getSiteId(), ScanJob.MANUAL);
    }

    /** 정기 검사 시각이 된 사이트를 검사한다 (SiteScheduler가 주기적으로 부름) */
    @Transactional
    public int runDue() {
        LocalDateTime now = LocalDateTime.now();
        List<SiteEntity> due = sites.findByEnabledTrueAndNextRunAtLessThanEqual(now);
        for (SiteEntity site : due) {
            site.setLastRunAt(now);
            site.setNextRunAt(nextRun(site, now));
            try {
                // 등록 뒤 주소의 실제 IP가 바뀌었을 수도 있으므로 검사 직전에 다시 확인
                String url = urlGuard.check(site.getBaseUrl());
                scanService.start(url, site.isLighthouse(), site.getMaxPages(), site.isAllPosts(), site.getSiteId(), ScanJob.SCHEDULED);
                log.info("정기 검사 시작: {} ({})", site.getName(), url);
            } catch (InvalidTargetException e) {
                log.warn("정기 검사를 시작하지 못함: {} - {}", site.getName(), e.getMessage());
            }
        }
        return due.size();
    }

    /**
     * 다음 정기 검사 시각. DAILY: 오늘 그 시각이 지났으면 내일, WEEKLY: 다음 그 요일 그 시각.
     * 정기 검사를 하지 않으면(NONE, 멈춤) null.
     */
    static LocalDateTime nextRun(SiteEntity site, LocalDateTime from) {
        if (!site.isScheduled()) return null;
        LocalTime at = LocalTime.of(site.getScheduleHour(), 0);
        if ("DAILY".equals(site.getScheduleType())) {
            LocalDateTime today = from.toLocalDate().atTime(at);
            return today.isAfter(from) ? today : today.plusDays(1);
        }
        DayOfWeek dow = DayOfWeek.of(site.getScheduleDow());
        LocalDate date = from.toLocalDate().with(TemporalAdjusters.nextOrSame(dow));
        LocalDateTime candidate = date.atTime(at);
        return candidate.isAfter(from) ? candidate : candidate.plusWeeks(1);
    }

    private static String nameOr(String name, String url) {
        if (name != null && !name.isBlank()) return name.strip().length() > 200 ? name.strip().substring(0, 200) : name.strip();
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    /** "a@x.com; b@y.com" → "a@x.com, b@y.com". 형식이 틀린 주소가 있으면 알려 준다. */
    static String normalizeEmails(String input) {
        if (input == null || input.isBlank()) return null;
        StringBuilder out = new StringBuilder();
        for (String part : input.split("[,;\\s]+")) {
            if (part.isBlank()) continue;
            if (!EMAIL.matcher(part).matches()) {
                throw new InvalidTargetException("메일 주소 형식이 올바르지 않습니다: " + part);
            }
            if (!out.isEmpty()) out.append(", ");
            out.append(part);
        }
        if (out.length() > 1000) throw new InvalidTargetException("메일 주소가 너무 많습니다.");
        return out.isEmpty() ? null : out.toString();
    }
}
