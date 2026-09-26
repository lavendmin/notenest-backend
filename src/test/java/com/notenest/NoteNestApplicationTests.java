package com.notenest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import com.notenest.service.BidServiceImpl;

@SpringBootTest(properties = {
        "spring.jwt.secret=test-secret-key-for-notenest-builds",
        "notenest.scheduling.enabled=false",
        // [NB5] 이 스모크 테스트는 datasource 를 지정하지 않아 로컬 프로필의 notenest DB 에 붙는다. 기동 부트스트랩이
        // 그 데이터로 로컬 Elasticsearch 색인을 만들지 않도록 끈다(DB 연결 자체는 기존 동작 — NB5 보고서의 확인 요청 참고).
        "notenest.search.bootstrap.enabled=false"
})
class NoteNestApplicationTests {

    @MockBean
    private BidServiceImpl bidService;

    @Test
    void contextLoads() {
    }

}
