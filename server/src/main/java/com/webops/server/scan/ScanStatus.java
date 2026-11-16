package com.webops.server.scan;

public enum ScanStatus {
    QUEUED("대기 중"),
    RUNNING("검사 중"),
    DONE("완료"),
    FAILED("실패");

    private final String label;

    ScanStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
