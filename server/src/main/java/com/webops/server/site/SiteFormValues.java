package com.webops.server.site;

import com.webops.server.scan.ScanScope;
import com.webops.server.store.SiteEntity;

/** 등록·설정 입력 칸에 다시 채워 넣을 값 (입력 오류로 돌아왔을 때, 또는 저장된 설정을 고칠 때) */
public record SiteFormValues(String urlValue, String nameValue, String scopeValue, boolean allPostsValue, String scheduleValue, int hourValue,
                             int dowValue, String emailValue, boolean lighthouseValue) {

    public static SiteFormValues of(SiteService.SiteForm f) {
        return new SiteFormValues(f.url(), f.name(), ScanScope.from(f.scope()).name(), f.allPosts(), f.schedule(), f.hour(), f.dow(), f.email(), f.lighthouse());
    }

    public static SiteFormValues of(SiteEntity s) {
        return new SiteFormValues(s.getBaseUrl(), s.getName(), s.getScope().name(), s.isAllPosts(), s.getScheduleType(), s.getScheduleHour(),
                s.getScheduleDow(), s.getNotifyEmail(), s.isLighthouse());
    }
}
