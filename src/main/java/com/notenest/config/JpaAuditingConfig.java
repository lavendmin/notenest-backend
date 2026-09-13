package com.notenest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화를 메인 애플리케이션 클래스에서 분리한 설정.
 *
 * <p>메인 클래스에 {@code @EnableJpaAuditing}이 있으면 {@code @WebMvcTest}/{@code @JsonTest} 같은
 * 슬라이스 테스트도 {@code jpaAuditingHandler}→{@code jpaMappingContext}(JPA)를 요구해 컨텍스트
 * 로드가 실패한다. 전체 앱과 JPA 슬라이스에서는 이 @Configuration 이 스캔·임포트되어 그대로 동작한다.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
