package com.notenest.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 경매 배치(@Scheduled, 10초 주기) 활성화.
 *
 * 메인 클래스에 두면 @DataJpaTest 같은 슬라이스 테스트도 그 설정을 물려받아 스케줄러가 켜진다.
 * 그러면 실제 BidServiceImpl 의 배치가 테스트 픽스처를 동시에 처리해 결과가 흔들린다(메일 중복 호출 등).
 * 그래서 별도 설정으로 분리하고(슬라이스 테스트는 이 클래스를 스캔하지 않는다), 전체 컨텍스트 테스트는
 * notenest.scheduling.enabled=false 로 끈다. 운영·로컬 실행은 기본값(true)으로 켜진다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "notenest.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
