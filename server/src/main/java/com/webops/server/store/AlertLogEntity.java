package com.webops.server.store;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * alert_log: 보낸 알림 기록.
 * status: SENT(보냄) / SKIPPED(메일 서버 설정이 없어 보내지 않음) / FAILED(보내기 실패)
 */
@Entity
@Table(name = "alert_log", indexes = @Index(name = "ix_alert_site", columnList = "site_id, created_at"))
public class AlertLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "alert_id")
    private Long alertId;

    @Column(name = "site_id")
    private Long siteId;

    @Column(name = "job_id", length = 64)
    private String jobId;

    /** 알림 종류를 쉼표로: SITE_DOWN, RECOVERED, NEW_BROKEN_LINK, SCAN_FAILED */
    @Column(name = "alert_type", length = 200, nullable = false)
    private String alertType;

    @Column(name = "subject", length = 500, nullable = false)
    private String subject;

    @Lob
    @Column(name = "body")
    private String body;

    @Column(name = "recipients", length = 1000)
    private String recipients;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "error_msg", length = 1000)
    private String error;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AlertLogEntity() {
    }

    public AlertLogEntity(Long siteId, String jobId, String alertType, String subject, String body, String recipients,
                          String status, String error, LocalDateTime createdAt) {
        this.siteId = siteId;
        this.jobId = jobId;
        this.alertType = alertType;
        this.subject = subject;
        this.body = body;
        this.recipients = recipients;
        this.status = status;
        this.error = error != null && error.length() > 1000 ? error.substring(0, 1000) : error;
        this.createdAt = createdAt;
    }

    public Long getAlertId() { return alertId; }
    public Long getSiteId() { return siteId; }
    public String getJobId() { return jobId; }
    public String getAlertType() { return alertType; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public String getRecipients() { return recipients; }
    public String getStatus() { return status; }
    public String getError() { return error; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public String getStatusLabel() {
        return switch (status) {
            case "SENT" -> "보냄";
            case "SKIPPED" -> "보내지 않음 (메일 설정 없음)";
            default -> "보내기 실패";
        };
    }
}
