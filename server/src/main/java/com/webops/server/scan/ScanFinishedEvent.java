package com.webops.server.scan;

/** 검사가 끝났을 때(완료·실패 모두) 알림 등 후속 처리에 알리는 이벤트. DB 저장이 끝난 뒤에 나온다. */
public record ScanFinishedEvent(ScanJob job) {
}
