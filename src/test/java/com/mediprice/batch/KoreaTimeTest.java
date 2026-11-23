package com.mediprice.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.TimeZone;

import org.junit.jupiter.api.Test;

class KoreaTimeTest {

    @Test
    void applyAsDefault는_JVM_기본_시간대를_Asia_Seoul로_바꿈() {
        TimeZone original = TimeZone.getDefault();
        try {
            KoreaTime.applyAsDefault();

            assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Seoul");
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
