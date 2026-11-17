package com.webops.server.store;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * site: 검사 대상 사이트 (기획서 3장) + 정기 검사 일정 · 알림 받을 메일.
 * 일정: NONE(정기 검사 안 함) / DAILY(매일 schedule_hour시) / WEEKLY(매주 schedule_dow요일 schedule_hour시)
 */
@Entity
@Table(name = "site")
public class SiteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "site_id")
    private Long siteId;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "base_url", length = 2000, nullable = false)
    private String baseUrl;

    @Column(name = "max_pages", nullable = false)
    private int maxPages;

    @Column(name = "lighthouse_yn", nullable = false)
    private boolean lighthouse;

    /** 게시판 글: true = 모든 게시물, false/null = 최신 게시물 1개만 */
    @Column(name = "all_posts_yn")
    private Boolean allPosts;

    @Column(name = "schedule_type", length = 16, nullable = false)
    private String scheduleType;

    /** 검사 시각 (0~23시) */
    @Column(name = "schedule_hour", nullable = false)
    private int scheduleHour;

    /** 매주 검사 요일 (1=월 ~ 7=일) */
    @Column(name = "schedule_dow", nullable = false)
    private int scheduleDow;

    /** 알림 받을 메일 (여러 개면 쉼표로) */
    @Column(name = "notify_email", length = 1000)
    private String notifyEmail;

    @Column(name = "enabled_yn", nullable = false)
    private boolean enabled;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected SiteEntity() {
    }

    public SiteEntity(String name, String baseUrl, LocalDateTime createdAt) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.createdAt = createdAt;
        this.enabled = true;
        this.scheduleType = "NONE";
        this.maxPages = 1;
        this.scheduleDow = 1;
    }

    public Long getSiteId() { return siteId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) { this.maxPages = maxPages; }
    public boolean isLighthouse() { return lighthouse; }
    public void setLighthouse(boolean lighthouse) { this.lighthouse = lighthouse; }
    public boolean isAllPosts() { return Boolean.TRUE.equals(allPosts); }
    public void setAllPosts(boolean allPosts) { this.allPosts = allPosts; }
    public String getScheduleType() { return scheduleType; }
    public void setScheduleType(String scheduleType) { this.scheduleType = scheduleType; }
    public int getScheduleHour() { return scheduleHour; }
    public void setScheduleHour(int scheduleHour) { this.scheduleHour = scheduleHour; }
    public int getScheduleDow() { return scheduleDow; }
    public void setScheduleDow(int scheduleDow) { this.scheduleDow = scheduleDow; }
    public String getNotifyEmail() { return notifyEmail; }
    public void setNotifyEmail(String notifyEmail) { this.notifyEmail = notifyEmail; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(LocalDateTime nextRunAt) { this.nextRunAt = nextRunAt; }
    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    /** 화면용 일정 설명: "매일 9시" / "매주 월요일 9시" / "정기 검사 안 함" */
    public String getScheduleLabel() {
        return switch (scheduleType) {
            case "DAILY" -> "매일 " + scheduleHour + "시";
            case "WEEKLY" -> "매주 " + DOW[Math.max(1, Math.min(7, scheduleDow)) - 1] + "요일 " + scheduleHour + "시";
            default -> "정기 검사 안 함";
        };
    }

    /** 검사 범위 (주소와 최대 페이지 수로 알아냄) */
    public com.webops.server.scan.ScanScope getScope() {
        return com.webops.server.scan.ScanScope.of(baseUrl, maxPages);
    }

    public boolean isScheduled() {
        return enabled && !"NONE".equals(scheduleType);
    }

    private static final String[] DOW = {"월", "화", "수", "목", "금", "토", "일"};
}
