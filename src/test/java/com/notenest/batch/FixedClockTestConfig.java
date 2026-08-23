package com.notenest.batch;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 배치 특성화/멱등성 테스트용 고정 Clock. 서비스와 픽스처가 같은 논리 시각을 공유하도록 해
 * "같은 시각으로 두 번 실행" 같은 시간 의존 시나리오를 결정적으로 검증한다.
 */
@TestConfiguration
public class FixedClockTestConfig {

    // 임의의 고정 시각 — 서비스(now(clock))와 픽스처(now())가 동일 clock 을 쓰므로 절대값은 무의미하다.
    static final Instant FIXED_INSTANT = Instant.parse("2026-06-01T03:00:00Z");

    @Bean
    public Clock clock() {
        return Clock.fixed(FIXED_INSTANT, ZoneId.systemDefault());
    }
}
