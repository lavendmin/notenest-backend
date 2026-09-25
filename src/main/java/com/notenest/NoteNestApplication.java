package com.notenest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

// @EnableJpaAuditing 은 JpaAuditingConfig 로 분리했다 — 웹/JSON 슬라이스 테스트가 JPA 없이도
// 컨텍스트를 로드할 수 있게 하기 위함(실제 타임스탬프는 Hibernate @CreationTimestamp 로 채워진다).
// @EnableScheduling 도 같은 이유로 SchedulingConfig 로 분리했다 — 슬라이스 테스트에서 배치가 돌지 않게.
@SpringBootApplication
public class NoteNestApplication {

    public static void main(String[] args) {
        // 톰캣 Timezone 설정
        // EC2에서도 Tomcat 서버의 시간을 서울 시간으로 변경해야 한다.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(NoteNestApplication.class, args);
    }

}
