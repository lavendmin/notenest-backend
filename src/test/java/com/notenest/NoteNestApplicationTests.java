package com.notenest;

import com.notenest.service.BidServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

/**
 * 전체 컨텍스트 기동 스모크 테스트.
 *
 * [NB5 리뷰 반영] 예전에는 datasource 를 지정하지 않아 로컬 프로필의 notenest DB 에 ddl-auto=update 로 붙었다 —
 * 엔티티가 바뀔 때마다 `./gradlew test` 가 개인 DB 스키마를 바꿨다(NB5 에서 bpm·musical_key 컬럼이 그렇게 추가됨).
 * 이제 다른 H2 계약 테스트와 같이 메모리 DB(create-drop)로 격리하고, test 프로필로 로컬 프로필 설정을 읽지 않는다.
 * Elasticsearch 는 기본 test 태스크가 닫힌 포트로 고정한다(build.gradle) — 로컬 notenest-es 에도 쓰지 않는다.
 */
@SpringBootTest(properties = {
        "spring.jwt.secret=test-secret-key-for-notenest-builds",
        "notenest.scheduling.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:context-smoke;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@ActiveProfiles("test")
class NoteNestApplicationTests {

    @MockBean
    private BidServiceImpl bidService;

    @Test
    void contextLoads() {
    }

}
