package com.notenest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 시간 소스를 빈으로 분리한다. 운영은 시스템 시계를 쓰고, 테스트는 고정 Clock 을 주입해
 * "같은 논리 시각으로 반복 실행" 같은 시간 의존 시나리오를 결정적으로 검증한다.
 *
 * 앱 기동 시 TimeZone 기본값을 Asia/Seoul 로 세팅하므로 systemDefaultZone() 이 그 존을 따른다
 * (기존 LocalDateTime.ofInstant(Instant.now(), ZoneId.systemDefault()) 와 동일한 시각).
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
