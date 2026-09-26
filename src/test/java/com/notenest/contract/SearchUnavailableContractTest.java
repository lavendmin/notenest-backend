package com.notenest.contract;

import com.notenest.service.BidServiceImpl;
import com.notenest.service.EmailService;
import com.notenest.storage.FakeObjectStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [NB5] 검색 엔진에 닿지 못할 때의 계약 — 목이 아니라 실제 어댑터·Elasticsearch 클라이언트로 검증한다.
 *
 * 클라이언트는 닫힌 포트(127.0.0.1:1)를 가리킨다. 애플리케이션은 Elasticsearch 없이도 뜨고, 검색어 요청만 503 이며,
 * 검색어 없는 목록은 기존 경로로 정상 응답한다. 연결 타임아웃이 짧아(1초) 요청이 오래 매달리지 않는다.
 */
@SpringBootTest(properties = {
        "spring.jwt.secret=test-secret-key-for-notenest-builds",
        "spring.datasource.url=jdbc:h2:mem:search-unavailable;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.elasticsearch.uris=http://127.0.0.1:1"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SearchUnavailableContractTest {

    @TestConfiguration
    static class FakeStorageConfig {
        @Bean
        @Primary
        FakeObjectStorage fakeObjectStorage() {
            return new FakeObjectStorage();
        }
    }

    @Autowired private MockMvc mockMvc;

    @MockBean private BidServiceImpl bidService;
    @MockBean private EmailService emailService;

    @Test
    @DisplayName("검색 엔진에 연결할 수 없으면 검색어 요청은 503 과 안내 메시지, 검색어 없는 목록은 200")
    void unreachableSearchEngine() throws Exception {
        long start = System.currentTimeMillis();
        String body = mockMvc.perform(get("/api/music/filter").param("searchTerm", "봄비"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(body).contains("검색을 일시적으로 사용할 수 없습니다");
        assertThat(elapsed).as("연결 거부는 타임아웃(1초 연결·3초 응답) 안에 끝난다").isLessThan(5_000);

        mockMvc.perform(get("/api/music/filter")).andExpect(status().isOk());
        mockMvc.perform(get("/api/music/filter").param("searchTerm", "   ")).andExpect(status().isOk());
    }
}
