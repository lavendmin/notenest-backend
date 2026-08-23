package com.notenest.contract;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notenest.domain.Music;
import com.notenest.repository.MusicRepository;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.notenest.service.BidServiceImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 경매 곡 목록 API(/api/music/filter)의 기능 계약을 고정하는 특성화 테스트.
 *
 * 목적: maxPrice 필터 경로를 DTO 프로젝션으로 리팩터할 때, "무엇을 돌려주는가"(계약)가
 *       바뀌지 않았음을 자동 증명한다. 성능(k6)이 아니라 응답 내용을 검사한다.
 *
 * 전제: 로컬 프로파일(application.properties: spring.profiles.active=local) → MariaDB 3311,
 *       seed-music.sql N=100 시드(status0 곡 존재), bidder@test.local 계정 존재.
 *       시드가 없으면 Assumption 으로 스킵한다(실패가 아니라 건너뜀).
 *
 * 인증: JWT 필터를 끄고(@AutoConfigureMockMvc(addFilters=false)) @WithMockUser 로
 *       SecurityContext 의 name 을 bidder 이메일로 채운다 → 컨트롤러가 findByEmail 로 조회.
 *
 * 측정 요청과 동일 형태: searchTerm=&sortBy=latest&page=0&maxPrice=100000&minPrice=0
 */
@SpringBootTest(properties = {
        "spring.jwt.secret=test-secret-key-for-notenest-builds",
        "spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(username = "bidder@test.local")
class MusicFilterContractTest {

    private static final String BIDDER = "bidder@test.local";
    private static final String FILTER = "/api/music/filter";
    private static final int MAX_PRICE = 100000; // 시드 곡 가격(11,000~15,000)을 모두 포함 → status0 전체가 대상

    @Autowired
    private MockMvc mockMvc;

    // 스케줄러(@Scheduled checkAuctionEnd)가 테스트 중 LOB 를 통짜 로딩하고 시드 데이터를
    // 변형(낙찰 처리·이메일)하는 것을 차단. 계약 테스트는 읽기만 해야 한다.
    @MockBean
    private BidServiceImpl bidService;

    @Autowired
    private MusicRepository musicRepository;

    @Autowired
    private ObjectMapper objectMapper;

    // 프로젝션 리팩터가 건드리면 안 되는 참조값: status=0 곡의 최신순 순서/총건수
    private List<UUID> referenceLatestUuids;
    private long referenceOngoingCount;

    @BeforeEach
    void loadReference() {
        Page<Music> ref = musicRepository.findAllOngoingMusicByOrderByCreatedAtDesc(PageRequest.of(0, 10));
        referenceOngoingCount = ref.getTotalElements();
        referenceLatestUuids = ref.getContent().stream().map(Music::getMusicUuid).collect(Collectors.toList());
        Assumptions.assumeTrue(referenceOngoingCount > 0,
                "시드된 진행중(status=0) 곡이 없어 계약 테스트를 건너뜁니다. seed-music.sql 실행 필요.");
    }

    private JsonNode getFilter(String sortBy) throws Exception {
        MvcResult res = performFilter(sortBy, 0, MAX_PRICE);
        return objectMapper.readTree(res.getResponse().getContentAsByteArray());
    }

    private MvcResult performFilter(String sortBy, int size, int maxPrice) throws Exception {
        var req = get(FILTER)
                .param("searchTerm", "")
                .param("sortBy", sortBy)
                .param("page", "0")
                .param("maxPrice", String.valueOf(maxPrice))
                .param("minPrice", "0")
                .with(r -> { r.setRemoteUser(BIDDER); return r; });
        if (size > 0) req = req.param("size", String.valueOf(size));
        return mockMvc.perform(req).andExpect(status().isOk()).andReturn();
    }

    // org.hibernate.SQL 로그를 캡처해 likes 테이블을 조회하는 SQL 문 수를 센다.
    private long countLikesQueries(int size) throws Exception {
        Logger sqlLogger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level previous = sqlLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sqlLogger.setLevel(Level.DEBUG);
        sqlLogger.addAppender(appender);
        try {
            performFilter("latest", size, MAX_PRICE);
        } finally {
            sqlLogger.detachAppender(appender);
            sqlLogger.setLevel(previous);
        }
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(sql -> sql.contains("likes"))
                .count();
    }

    @Test
    @DisplayName("maxPrice 필터 응답에 목록 계약 필드가 모두 존재한다 (커버 이미지 포함)")
    void maxPriceFilter_hasAllContractFields() throws Exception {
        JsonNode root = getFilter("latest");
        JsonNode content = root.path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.size()).isGreaterThan(0);

        JsonNode first = content.get(0);
        assertThat(first.hasNonNull("musicUuid")).as("musicUuid").isTrue();
        assertThat(first.hasNonNull("title")).as("title").isTrue();
        assertThat(first.hasNonNull("startingPrice")).as("startingPrice").isTrue();
        assertThat(first.hasNonNull("userNickName")).as("userNickName(작곡가)").isTrue();
        assertThat(first.hasNonNull("auctionEndTime")).as("auctionEndTime(마감시각)").isTrue();
        assertThat(first.has("likeCount")).as("likeCount").isTrue();
        assertThat(first.has("likedByUser")).as("likedByUser(좋아요 여부)").isTrue();
        assertThat(first.hasNonNull("image")).as("image(커버 이미지)는 목록 계약에 반드시 포함").isTrue();
    }

    @Test
    @DisplayName("총 건수가 참조(진행중 곡 수)와 같다 — 필터가 대상 집합을 바꾸지 않는다")
    void maxPriceFilter_totalElementsMatchesReference() throws Exception {
        JsonNode root = getFilter("latest");
        // maxPrice=100000 은 시드 가격대를 모두 포함하므로 status=0 전체와 같아야 한다.
        assertThat(root.path("totalElements").asLong()).isEqualTo(referenceOngoingCount);
    }

    @Test
    @DisplayName("최신순 정렬 순서가 참조 리포지토리 순서와 동일하다")
    void latestSort_orderMatchesReference() throws Exception {
        JsonNode content = getFilter("latest").path("content");
        List<UUID> apiUuids = content.findValues("musicUuid").stream()
                .map(n -> UUID.fromString(n.asText())).collect(Collectors.toList());
        assertThat(apiUuids).isEqualTo(referenceLatestUuids);
    }

    @Test
    @DisplayName("커버 이미지 바이트가 원본 엔티티와 정확히 일치한다 (필드 존재만이 아니라 바이트)")
    void coverImageBytes_matchEntity() throws Exception {
        JsonNode first = getFilter("latest").path("content").get(0);
        UUID id = UUID.fromString(first.path("musicUuid").asText());
        byte[] apiImage = Base64.getDecoder().decode(first.path("image").asText());

        Music entity = musicRepository.findById(id).orElseThrow();
        assertThat(apiImage).isEqualTo(entity.getImage());
    }

    @Test
    @DisplayName("가격순/좋아요순 분기도 200과 계약 필드를 유지한다")
    void otherSorts_keepContract() throws Exception {
        for (String sort : List.of("price", "like")) {
            JsonNode content = getFilter(sort).path("content");
            assertThat(content.size()).as("sort=" + sort + " content").isGreaterThan(0);
            assertThat(content.get(0).hasNonNull("image")).as("sort=" + sort + " image").isTrue();
        }
    }

    // ── 아래는 리팩터(필터 경로 프로젝션 + audio 제거) 후에만 통과하는 목표 계약 ──
    //    지금 코드(엔티티 통짜 로딩 + fromMusic)에서는 audio 가 JSON·SQL 양쪽에 존재하므로
    //    빌드를 초록으로 유지하기 위해 @Disabled. 3단계 구현 커밋에서 활성화한다.

    @Test
    @DisplayName("모든 정렬/필터 분기 JSON 응답에 audio 필드가 없다")
    void audioAbsentInJson() throws Exception {
        for (String sort : List.of("latest", "price", "like")) {
            JsonNode content = getFilter(sort).path("content");
            for (JsonNode el : content) {
                assertThat(el.has("audio")).as("sort=" + sort + " audio 부재").isFalse();
            }
        }
    }

    @Test
    @DisplayName("maxPrice 필터 요청의 Hibernate SELECT 절에 audio 컬럼이 없다")
    void audioAbsentInSqlSelect() throws Exception {
        Logger sqlLogger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level previous = sqlLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sqlLogger.setLevel(Level.DEBUG);
        sqlLogger.addAppender(appender);
        try {
            getFilter("latest");
        } finally {
            sqlLogger.detachAppender(appender);
            sqlLogger.setLevel(previous);
        }

        List<String> selects = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(sql -> sql.contains("music"))
                .collect(Collectors.toList());
        assertThat(selects).as("music 관련 SQL 이 최소 1건 캡처되어야 한다").isNotEmpty();
        // JSON 필드 제거가 아니라 실제 SELECT 절에서 LOB 컬럼이 빠졌는지 검증
        assertThat(selects).noneMatch(sql -> sql.contains(".audio"));
    }

    @Test
    @DisplayName("좋아요 조회 쿼리 수가 페이지 크기(1·20·50)에 비례하지 않는다 (N+1 제거)")
    void likesQuery_doesNotScaleWithPageSize() throws Exception {
        long q1 = countLikesQueries(1);
        long q20 = countLikesQueries(20);
        long q50 = countLikesQueries(50);

        // N+1이면 1, 20, 50 으로 늘어난다. IN 배치면 페이지 크기와 무관하게 최대 1건.
        assertThat(q1).as("size=1 likes 쿼리").isLessThanOrEqualTo(1);
        assertThat(q20).as("size=20 likes 쿼리").isLessThanOrEqualTo(1);
        assertThat(q50).as("size=50 likes 쿼리").isLessThanOrEqualTo(1);
        assertThat(q20).as("페이지 크기와 무관하게 일정").isEqualTo(q1);
        assertThat(q50).as("페이지 크기와 무관하게 일정").isEqualTo(q1);
    }
}
