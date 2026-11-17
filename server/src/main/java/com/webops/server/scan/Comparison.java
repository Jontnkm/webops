package com.webops.server.scan;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 재검사 비교: 같은 주소 · 같은 검사 범위의 지난 검사와 발견 항목(fingerprint)을 맞춰 본다.
 * - 새로 생김: 이번에만 있는 문제
 * - 해결됨: 지난번에는 있었는데 이번에 없는 문제
 * - 그대로: 둘 다 있는 문제
 * '직접 확인 필요' 항목은 비교하지 않는다.
 */
public record Comparison(
        String previousJobId,
        LocalDateTime previousAt,
        Integer previousScore,
        Integer currentScore,
        Set<String> newFingerprints,
        int keptCount,
        List<ScanReport.Finding> resolved) {

    public int newCount() {
        return newFingerprints.size();
    }

    public int resolvedCount() {
        return resolved.size();
    }

    public boolean isNew(ScanReport.Finding f) {
        return f != null && !f.manualCheck() && newFingerprints.contains(f.fingerprint());
    }

    /** 점수 변화 (+5, -3, 0). 둘 중 하나라도 없으면 null */
    public Integer scoreDelta() {
        return previousScore == null || currentScore == null ? null : currentScore - previousScore;
    }
}
