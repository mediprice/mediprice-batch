package com.mediprice.batch;

import java.time.Clock;
import java.time.ZoneId;
import java.util.TimeZone;

/** 배치가 시각을 해석하는 기준 시간대. 실행 환경의 TZ와 무관하게 Asia/Seoul. */
public final class KoreaTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private KoreaTime() {
    }

    public static Clock clock() {
        return Clock.system(ZONE);
    }

    /** Spring Batch 실행 기록·JDBC 세션 시간대가 따르는 JVM 기본 시간대를 Asia/Seoul로 고정한다. */
    public static void applyAsDefault() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE));
    }
}
