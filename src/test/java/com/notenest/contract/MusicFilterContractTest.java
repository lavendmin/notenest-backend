package com.notenest.contract;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notenest.domain.Likes;
import com.notenest.domain.MediaObject;
import com.notenest.domain.Music;
import com.notenest.domain.MusicalKey;
import com.notenest.domain.User;
import com.notenest.repository.BidRepository;
import com.notenest.repository.LikeRepository;
import com.notenest.repository.MusicRepository;
import com.notenest.repository.PaymentRepository;
import com.notenest.repository.UserRepository;
import com.notenest.service.BidServiceImpl;
import com.notenest.storage.FakeObjectStorage;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 경매 곡 목록 API(/api/music/filter)의 기능 계약을 고정하는 특성화 테스트.
 *
 * 목적: 목록 경로를 DTO 프로젝션으로 리팩터할 때(N1), 그리고 커버를 S3 URL로 바꿀 때(NB1)
 *       "무엇을 돌려주는가"(계약)가 바뀌지 않았음을 자동 증명한다. 성능(k6)이 아니라 응답 내용을 검사한다.
 *
 * DB: 실제 MariaDB(로컬 docker, 3311)에서 실행한다 — QueryDSL 이 생성하는 SQL, MariaDB 의 정렬(nulls last)·
 *     LIKE 동작, SELECT 절의 열 구성을 그대로 검증하기 위해서다. 단 성능 측정용 DB(notenest)와 분리된
 *     <b>계약 테스트 전용 DB(notenest_contract)</b>를 쓰고 스키마를 테스트가 만들고 지운다(create-drop).
 *     측정용 시드 규모(N=100·500)와 무관하게 항상 같은 결과가 나온다.
 *
 * 픽스처: 매 테스트 전에 진행중 6곡 + 마감 1곡을 직접 만든다(아래 표). 최신순·가격순·좋아요순의 기대 순서가
 *        모두 결정적이 되도록 생성 시각·가격·좋아요 수를 서로 다르게 설계했고, 기대값은 DB 조회가 아니라
 *        이 표에서 나온다. 마감 곡(G)은 좋아요 최다·가장 최신이라 목록에 섞이면 곧바로 드러난다.
 *
 * <pre>
 * 곡 | 제목          | 작곡가      | 장르 | 해시태그       | 시작가 | 최고가 | 좋아요 | 생성(분 전) | bidder 좋아요
 * A  | alpha song    | composer-a | POP  | #seed          | 10000 | 90000 | 5     | 60         | O
 * B  | beta song     | composer-a | POP  | #seed          | 20000 | 90000 | 3     | 50         |
 * C  | gamma song    | composer-b | POP  | #seed          | 31000 | null  | 1     | 40         | O
 * D  | delta song    | composer-b | POP  | #seed          | 29000 | null  | 0     | 30         |
 * E  | epsilon track | composer-a | JAZZ | #jazzy,#only   | 11000 | null  | 2     | 20         |   (부제 qq-unique-subtitle-qq)
 * F  | zeta track    | composer-b | POP  | #seed          | 10000 | 12000 | 4     | 10         |
 * G  | ended song    | composer-a | POP  | #seed          | 10000 | 11000 | 9     | 5          |   (status=1 마감 → 제외)
 * </pre>
 *
 * [NB5] BPM·키(검색 필터)와 상세 설명: A 90 A_MINOR / B 120 C_MAJOR / C 없음 / D 95 A_MINOR /
 *       E 140 F_SHARP_MINOR, 상세 설명 qq-details-only-qq / F 88 키 없음 / G 90 A_MINOR(마감).
 *
 * 인증: JWT 필터를 끄고(@AutoConfigureMockMvc(addFilters=false)) @WithMockUser 로
 *       SecurityContext 의 name 을 bidder 이메일로 채운다 → 서비스가 findByEmail 로 조회해 좋아요 여부를 채운다.
 *       비로그인 목록 공개 계약은 MediaAccessContractTest 가 보안 필터를 켠 채 검증한다.
 */
@SpringBootTest(properties = {
        "spring.jwt.secret=test-secret-key-for-notenest-builds",
        "notenest.scheduling.enabled=false",
        // 성능 측정 DB(notenest)와 분리된 계약 테스트 전용 DB. 없으면 드라이버가 만든다.
        "spring.datasource.url=jdbc:mariadb://localhost:3311/notenest_contract?createDatabaseIfNotExist=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(username = "bidder@test.local")
class MusicFilterContractTest {

    // 커버 URL 발급을 AWS 설정 없이 하려고 저장소를 fake 로 바꾼다(서명 대신 키·ttl 이 URL 에 담긴다).
    // 이 테스트의 관심사는 목록 SQL·정렬·필터 계약이지 S3 연동이 아니다.
    @TestConfiguration
    static class FakeStorageConfig {
        @Bean
        @Primary
        FakeObjectStorage fakeObjectStorage() {
            return new FakeObjectStorage();
        }
    }

    private static final String BIDDER = "bidder@test.local";
    private static final String FILTER = "/api/music/filter";
    private static final int MAX_PRICE = 100000; // 픽스처 가격(10,000~90,000)을 모두 포함 → 진행중 전체가 대상
    private static final int ONGOING_COUNT = 6;
    // 한 페이지에 진행중 전체가 들어가는 크기. 픽스처 규모에 맞춘 값이며 큰 시드를 전제로 하지 않는다.
    private static final String ONE_PAGE = "10";

    @Autowired
    private MockMvc mockMvc;

    // 스케줄러(@Scheduled 경매 배치)가 테스트 중 픽스처를 변형(낙찰 처리·이메일)하는 것을 차단한다.
    @MockBean
    private BidServiceImpl bidService;

    @Autowired
    private MusicRepository musicRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID a, b, c, d, e, f, g;

    @BeforeEach
    void createFixtures() {
        // 정리와 생성은 트랜잭션을 나눈다 — 한 영속성 컨텍스트에서는 flush 시 INSERT 가 DELETE 보다 먼저 실행되어
        // 같은 이메일(unique) 재생성이 중복 키로 실패한다.
        transactionTemplate.executeWithoutResult(tx -> {
            likeRepository.deleteAll();
            paymentRepository.deleteAll();
            bidRepository.deleteAll();
            musicRepository.deleteAll();
            userRepository.deleteAll();
        });
        transactionTemplate.executeWithoutResult(tx -> {
            User bidder = saveUser(BIDDER, "bidder");
            User composerA = saveUser("composer-a@test.local", "composer-a");
            User composerB = saveUser("composer-b@test.local", "composer-b");

            a = saveMusic("alpha song", composerA, "POP", "#seed", null, 10000L, 90000L, 5, 60, 0);
            b = saveMusic("beta song", composerA, "POP", "#seed", null, 20000L, 90000L, 3, 50, 0);
            c = saveMusic("gamma song", composerB, "POP", "#seed", null, 31000L, null, 1, 40, 0);
            d = saveMusic("delta song", composerB, "POP", "#seed", null, 29000L, null, 0, 30, 0);
            e = saveMusic("epsilon track", composerA, "JAZZ", "#jazzy,#only", "qq-unique-subtitle-qq", 11000L, null, 2, 20, 0);
            f = saveMusic("zeta track", composerB, "POP", "#seed", null, 10000L, 12000L, 4, 10, 0);
            g = saveMusic("ended song", composerA, "POP", "#seed", null, 10000L, 11000L, 9, 5, 1);

            like(bidder, a);
            like(bidder, c);

            setAttributes(a, 90, MusicalKey.A_MINOR, null);
            setAttributes(b, 120, MusicalKey.C_MAJOR, null);
            setAttributes(d, 95, MusicalKey.A_MINOR, null);
            setAttributes(e, 140, MusicalKey.F_SHARP_MINOR, "qq-details-only-qq");
            setAttributes(f, 88, null, null);
            setAttributes(g, 90, MusicalKey.A_MINOR, null);
        });
    }

    private void setAttributes(UUID id, Integer bpm, MusicalKey key, String details) {
        entityManager.createQuery("update Music m set m.bpm = :bpm, m.musicalKey = :key, m.details = :details where m.musicUuid = :id")
                .setParameter("bpm", bpm)
                .setParameter("key", key)
                .setParameter("details", details)
                .setParameter("id", id)
                .executeUpdate();
    }

    // --- 픽스처 헬퍼 ---

    private User saveUser(String email, String nickname) {
        User user = new User();
        user.setEmail(email);
        user.setNickname(nickname);
        user.setName(nickname);
        user.setPassword("x");
        user.setRole("ROLE_USER");
        user.setEmailVerified(true);
        user.setAgreement(true);
        return userRepository.save(user);
    }

    private UUID saveMusic(String title, User owner, String genre, String hashtag, String subtitle,
                           Long startingPrice, Long highestBid, int likeCount, int createdMinutesAgo, int status) {
        Music music = new Music();
        music.setTitle(title);
        music.setSubtitle(subtitle);
        music.setUser(owner);
        music.setMajorGenre(genre);
        music.setHashtag(hashtag);
        music.setStartingPrice(startingPrice);
        music.setCurrentHighestBid(highestBid);
        music.setLikeCount(likeCount);
        music.setStatus(status);
        music.setAuctionEndTime(status == 0 ? LocalDateTime.now().plusDays(3) : LocalDateTime.now().minusDays(1));
        UUID musicUuid = UUID.randomUUID();
        music.setMusicUuid(musicUuid);
        music.setCover(new MediaObject(coverKey(musicUuid, title), "image/png", 10L, null));
        music.setFullDemo(new MediaObject("music/" + musicUuid + "/full-demo/f", "audio/mpeg", 10L, null));
        music.setAuctionFailureEmailSent(false);
        music.setShowAllBids(false);
        music.setPopularComposer(false);
        music.setSteadyWorkComposer(false);
        music.setHitSongComposer(false);
        UUID id = musicRepository.saveAndFlush(music).getMusicUuid();
        // created_at 은 @CreationTimestamp·updatable=false 라 저장 시 현재 시각으로 덮인다.
        // 최신순 기대 순서가 결정적이도록 벌크 UPDATE 로 서로 다른 시각을 심는다.
        entityManager.createQuery("update Music m set m.createdAt = :t where m.musicUuid = :id")
                .setParameter("t", LocalDateTime.now().minusMinutes(createdMinutesAgo))
                .setParameter("id", id)
                .executeUpdate();
        return id;
    }

    private void like(User user, UUID musicId) {
        Likes like = new Likes();
        like.setUser(user);
        like.setMusic(musicRepository.getReferenceById(musicId));
        likeRepository.save(like);
    }

    private static String coverKey(UUID musicUuid, String title) {
        return "music/" + musicUuid + "/cover/" + title.replace(' ', '-');
    }

    // --- 요청 헬퍼 ---

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

    // 임의 파라미터 조합으로 /filter 를 호출해 JSON 루트를 돌려준다 (page 미지정 시 0).
    private JsonNode getFilterWith(Map<String, String> params) throws Exception {
        var req = get(FILTER).with(r -> { r.setRemoteUser(BIDDER); return r; });
        if (!params.containsKey("page")) req = req.param("page", "0");
        for (var entry : params.entrySet()) req = req.param(entry.getKey(), entry.getValue());
        MvcResult res = mockMvc.perform(req).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsByteArray());
    }

    // 400 이 기대되는 요청 — 본문 message 를 돌려준다.
    private String getFilterExpectingBadRequest(Map<String, String> params) throws Exception {
        var req = get(FILTER).with(r -> { r.setRemoteUser(BIDDER); return r; });
        for (var entry : params.entrySet()) req = req.param(entry.getKey(), entry.getValue());
        MvcResult res = mockMvc.perform(req).andExpect(status().isBadRequest()).andReturn();
        return res.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // 응답 content 의 musicUuid 를 순서 있는 리스트 / 집합으로 — 결과 집합·순서 비교용.
    private static List<UUID> uuidList(JsonNode root) {
        return root.path("content").findValues("musicUuid").stream()
                .map(n -> UUID.fromString(n.asText())).collect(Collectors.toList());
    }

    private static Set<UUID> uuidSet(JsonNode root) {
        return Set.copyOf(uuidList(root));
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

    // --- 필드·건수 ---

    @Test
    @DisplayName("maxPrice 필터 응답에 목록 계약 필드가 모두 존재한다 (커버 URL 포함)")
    void maxPriceFilter_hasAllContractFields() throws Exception {
        JsonNode content = getFilter("latest").path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.size()).isEqualTo(ONGOING_COUNT);

        JsonNode first = content.get(0);
        assertThat(first.hasNonNull("musicUuid")).as("musicUuid").isTrue();
        assertThat(first.hasNonNull("title")).as("title").isTrue();
        assertThat(first.hasNonNull("startingPrice")).as("startingPrice").isTrue();
        assertThat(first.hasNonNull("userNickName")).as("userNickName(작곡가)").isTrue();
        assertThat(first.hasNonNull("auctionEndTime")).as("auctionEndTime(마감시각)").isTrue();
        assertThat(first.has("likeCount")).as("likeCount").isTrue();
        assertThat(first.has("likedByUser")).as("likedByUser(좋아요 여부)").isTrue();
        assertThat(first.hasNonNull("coverUrl")).as("coverUrl(커버)은 목록 계약에 반드시 포함").isTrue();
        assertThat(first.has("image")).as("base64 커버는 더 이상 싣지 않는다").isFalse();
    }

    @Test
    @DisplayName("총 건수는 진행중 곡 수와 같고 마감 곡은 제외된다")
    void maxPriceFilter_totalElementsExcludesEndedMusic() throws Exception {
        JsonNode root = getFilter("latest");
        assertThat(root.path("totalElements").asLong()).isEqualTo(ONGOING_COUNT);
        assertThat(uuidSet(root)).doesNotContain(g);
    }

    // --- 정렬 ---

    @Test
    @DisplayName("최신순 정렬: 생성 시각 내림차순의 예상 UUID 순서")
    void latestSort_expectedOrder() throws Exception {
        assertThat(uuidList(getFilter("latest"))).containsExactly(f, e, d, c, b, a);
    }

    @Test
    @DisplayName("가격순 정렬: 최고가 DESC(null 마지막) → 동가·null 구간은 시작가 DESC — 예상 UUID 순서")
    void priceSort_expectedOrderWithTieBreakAndNullsLast() throws Exception {
        // B·A 는 최고가 90000 동가 → 시작가 20000 > 10000. F 는 최고가 12000.
        // C·D·E 는 최고가 null → 맨 뒤, 시작가 31000 > 29000 > 11000.
        assertThat(uuidList(getFilter("price"))).containsExactly(b, a, f, c, d, e);
    }

    @Test
    @DisplayName("좋아요순 정렬: likeCount 내림차순의 예상 UUID 순서")
    void likeSort_expectedOrder() throws Exception {
        assertThat(uuidList(getFilter("like"))).containsExactly(a, f, b, e, c, d);
    }

    @Test
    @DisplayName("likedByUser 매핑: bidder 가 실제로 좋아요한 곡(A·C)만 true")
    void likedByUser_mapsOnlyLikedMusic() throws Exception {
        Map<UUID, Boolean> likedByUuid = new HashMap<>();
        for (JsonNode el : getFilter("latest").path("content")) {
            likedByUuid.put(UUID.fromString(el.path("musicUuid").asText()), el.path("likedByUser").asBoolean());
        }
        assertThat(likedByUuid).containsOnly(
                Map.entry(a, true), Map.entry(b, false), Map.entry(c, true),
                Map.entry(d, false), Map.entry(e, false), Map.entry(f, false));
    }

    @Test
    @DisplayName("정렬된 응답의 Page 메타데이터에 정렬 정보가 담긴다 (sort.sorted=true, 기존 필터 경로 계약)")
    void pageMetadata_carriesSortInfo() throws Exception {
        for (String sort : List.of("latest", "price", "like")) {
            JsonNode root = getFilterWith(Map.of("sortBy", sort, "maxPrice", String.valueOf(MAX_PRICE), "minPrice", "0"));
            assertThat(root.path("sort").path("sorted").asBoolean()).as("sortBy=" + sort + " sort.sorted").isTrue();
            assertThat(root.path("sort").path("unsorted").asBoolean()).as("sortBy=" + sort + " sort.unsorted").isFalse();
            assertThat(root.path("pageable").path("sort").path("sorted").asBoolean()).as("pageable.sort.sorted").isTrue();
        }
    }

    @Test
    @DisplayName("sortBy 단독 요청(필터 없음)도 정렬이 적용된다 — price/like 가 최신순으로 무시되지 않는다")
    void sortByOnly_isHonoredWithoutFilters() throws Exception {
        assertThat(uuidList(getFilterWith(Map.of("sortBy", "price")))).containsExactly(b, a, f, c, d, e);
        assertThat(uuidList(getFilterWith(Map.of("sortBy", "like")))).containsExactly(a, f, b, e, c, d);
        assertThat(uuidList(getFilterWith(Map.of("sortBy", "latest")))).containsExactly(f, e, d, c, b, a);
    }

    @Test
    @DisplayName("페이지 크기보다 결과가 많으면 잘리고, 페이지를 넘기면 이어진다 (페이지 계약)")
    void pagination_splitsResultsBySize() throws Exception {
        JsonNode page0 = getFilterWith(Map.of("sortBy", "latest", "size", "4", "page", "0"));
        JsonNode page1 = getFilterWith(Map.of("sortBy", "latest", "size", "4", "page", "1"));

        assertThat(uuidList(page0)).containsExactly(f, e, d, c);
        assertThat(uuidList(page1)).containsExactly(b, a);
        assertThat(page0.path("totalElements").asLong()).isEqualTo(ONGOING_COUNT);
        assertThat(page0.path("totalPages").asInt()).isEqualTo(2);
    }

    // --- 검색·필터 ---

    @Test
    @DisplayName("검색 결과 집합: 제목·작곡가 닉네임(조인) 매칭, 무매칭 0건")
    void search_resultSetMatchesFixture() throws Exception {
        assertThat(uuidSet(getFilterWith(Map.of("searchTerm", "song", "size", ONE_PAGE)))).containsExactlyInAnyOrder(a, b, c, d);
        assertThat(uuidSet(getFilterWith(Map.of("searchTerm", "composer-b", "size", ONE_PAGE)))).containsExactlyInAnyOrder(c, d, f);
        assertThat(getFilterWith(Map.of("searchTerm", "zz-no-such-term-zz")).path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("필드별 검색(부제·장르·해시태그)과 장르·해시태그 필터가 정확히 그 곡만 돌려준다")
    void fieldSpecificSearchAndFilters_returnExactUuids() throws Exception {
        // searchTerm 이 부제·장르·해시태그 각각을 타는지 — 정확히 E 하나
        assertThat(uuidSet(getFilterWith(Map.of("searchTerm", "unique-subtitle", "size", ONE_PAGE)))).containsExactly(e);
        assertThat(uuidSet(getFilterWith(Map.of("searchTerm", "JAZZ", "size", ONE_PAGE)))).containsExactly(e);
        assertThat(uuidSet(getFilterWith(Map.of("searchTerm", "#jazzy", "size", ONE_PAGE)))).containsExactly(e);

        // majorGenre 필터(equals): JAZZ → E, POP → 진행중 나머지(마감 G 제외)
        assertThat(uuidSet(getFilterWith(Map.of("majorGenre", "JAZZ", "size", ONE_PAGE)))).containsExactly(e);
        assertThat(uuidSet(getFilterWith(Map.of("majorGenre", "POP", "size", ONE_PAGE)))).containsExactlyInAnyOrder(a, b, c, d, f);
        assertThat(getFilterWith(Map.of("majorGenre", "NO_SUCH_GENRE")).path("totalElements").asLong()).isZero();

        // hashtag 필터(콤마 분리 AND, 각 LIKE): "#jazzy,#only" → E / "#seed" → 나머지 / 무매칭 0
        assertThat(uuidSet(getFilterWith(Map.of("hashtag", "#jazzy,#only", "size", ONE_PAGE)))).containsExactly(e);
        assertThat(uuidSet(getFilterWith(Map.of("hashtag", "#seed", "size", ONE_PAGE)))).containsExactlyInAnyOrder(a, b, c, d, f);
        assertThat(getFilterWith(Map.of("hashtag", "#no-such-tag")).path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("가격 경계(min/max)의 결과 집합이 coalesce(최고입찰가, 시작가) 기준과 같다")
    void priceBoundary_resultSetMatchesFixture() throws Exception {
        // 유효 가격: A 90000, B 90000, C 31000, D 29000, E 11000, F 12000 (경계값 포함)
        assertThat(uuidSet(getFilterWith(Map.of("maxPrice", "11000", "size", ONE_PAGE)))).containsExactly(e);
        assertThat(uuidSet(getFilterWith(Map.of("maxPrice", "12000", "size", ONE_PAGE)))).containsExactlyInAnyOrder(e, f);
        assertThat(uuidSet(getFilterWith(Map.of("minPrice", "29000", "size", ONE_PAGE)))).containsExactlyInAnyOrder(a, b, c, d);
        assertThat(uuidSet(getFilterWith(Map.of("minPrice", "11000", "maxPrice", "12000", "size", ONE_PAGE)))).containsExactlyInAnyOrder(e, f);
        // 최고가가 있으면 시작가가 아니라 최고가로 판단한다 — A 는 시작가 10000 이지만 최고가 90000 이라 제외
        assertThat(uuidSet(getFilterWith(Map.of("maxPrice", "10000", "size", ONE_PAGE)))).isEmpty();
    }

    // --- 커버·음원 ---

    @Test
    @DisplayName("커버 URL 이 그 곡의 커버 객체 키를 가리킨다 (필드 존재만이 아니라 키)")
    void coverUrl_pointsToOwnCoverKey() throws Exception {
        JsonNode first = getFilter("latest").path("content").get(0); // 최신순 첫 곡 = F
        assertThat(UUID.fromString(first.path("musicUuid").asText())).isEqualTo(f);
        assertThat(first.path("coverUrl").asText()).contains(coverKey(f, "zeta track"));
    }

    @Test
    @DisplayName("무필터 요청(maxPrice 없음)도 커버 URL 포함·audio 제외 계약을 유지한다")
    void noFilterRequest_keepsContract() throws Exception {
        MvcResult res = mockMvc.perform(get(FILTER)
                        .param("page", "0")
                        .with(r -> { r.setRemoteUser(BIDDER); return r; }))
                .andExpect(status().isOk()).andReturn();
        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsByteArray());

        assertThat(root.path("totalElements").asLong()).isEqualTo(ONGOING_COUNT);
        for (JsonNode el : root.path("content")) {
            assertThat(el.hasNonNull("coverUrl")).as("무필터 커버 URL 포함").isTrue();
            assertThat(el.has("audio")).as("무필터 audio 부재").isFalse();
        }
    }

    @Test
    @DisplayName("검색어(searchTerm) 요청도 커버 URL 포함·audio 제외 계약을 유지한다")
    void searchRequest_keepsContract() throws Exception {
        MvcResult res = mockMvc.perform(get(FILTER)
                        .param("page", "0")
                        .param("sortBy", "latest")
                        .param("searchTerm", "song")
                        .with(r -> { r.setRemoteUser(BIDDER); return r; }))
                .andExpect(status().isOk()).andReturn();
        JsonNode content = objectMapper.readTree(res.getResponse().getContentAsByteArray()).path("content");

        assertThat(content.size()).as("검색 결과 존재").isEqualTo(4);
        for (JsonNode el : content) {
            assertThat(el.hasNonNull("coverUrl")).as("검색 커버 URL 포함").isTrue();
            assertThat(el.has("audio")).as("검색 audio 부재").isFalse();
        }
    }

    // 음원(audio)이 목록 계약에서 제외됨을 JSON·실제 SQL SELECT 양쪽에서 검증한다.

    @Test
    @DisplayName("모든 정렬/필터 분기 JSON 응답에 audio 필드가 없다")
    void audioAbsentInJson() throws Exception {
        for (String sort : List.of("latest", "price", "like")) {
            for (JsonNode el : getFilter(sort).path("content")) {
                assertThat(el.has("audio")).as("sort=" + sort + " audio 부재").isFalse();
            }
        }
    }

    @Test
    @DisplayName("maxPrice 필터 요청의 Hibernate SELECT 절에 미디어 바이트 컬럼(image·audio)이 없다")
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
        assertThat(selects).noneMatch(sql -> sql.contains(".audio") || sql.contains(".image"));
    }

    @Test
    @DisplayName("좋아요 조회 쿼리 수가 페이지 크기(1·3·6)에 비례하지 않는다 (N+1 제거)")
    void likesQuery_doesNotScaleWithPageSize() throws Exception {
        long q1 = countLikesQueries(1);
        long q3 = countLikesQueries(3);
        long q6 = countLikesQueries(6);

        // N+1이면 1, 3, 6 으로 늘어난다. IN 배치면 페이지 크기와 무관하게 최대 1건.
        assertThat(q1).as("size=1 likes 쿼리").isLessThanOrEqualTo(1);
        assertThat(q3).as("size=3 likes 쿼리").isLessThanOrEqualTo(1);
        assertThat(q6).as("size=6 likes 쿼리").isLessThanOrEqualTo(1);
        assertThat(q3).as("페이지 크기와 무관하게 일정").isEqualTo(q1);
        assertThat(q6).as("페이지 크기와 무관하게 일정").isEqualTo(q1);
    }

    // --- [NB5] 검색어 경로 기능 동등성: Phase 3 에서 검색 엔진으로 옮길 때 유지해야 할 계약 ---

    @Test
    @DisplayName("[NB5] 검색어 + 가격·장르·해시태그 필터는 AND 로 결합된다")
    void search_combinesWithFilters() throws Exception {
        assertThat(uuidSet(getFilterWith(Map.of("searchTerm", "song", "maxPrice", "30000", "size", ONE_PAGE)))).containsExactly(d);
        assertThat(uuidSet(getFilterWith(Map.of("searchTerm", "track", "majorGenre", "POP", "size", ONE_PAGE)))).containsExactly(f);
        assertThat(uuidSet(getFilterWith(Map.of("searchTerm", "composer-a", "hashtag", "#seed", "size", ONE_PAGE))))
                .containsExactlyInAnyOrder(a, b);
    }

    @Test
    @DisplayName("[NB5] 검색어가 있어도 명시한 정렬(최신·가격·좋아요)이 그대로 적용된다")
    void search_honorsExplicitSort() throws Exception {
        assertThat(uuidList(getFilterWith(Map.of("searchTerm", "song", "sortBy", "latest")))).containsExactly(d, c, b, a);
        assertThat(uuidList(getFilterWith(Map.of("searchTerm", "song", "sortBy", "price")))).containsExactly(b, a, c, d);
        assertThat(uuidList(getFilterWith(Map.of("searchTerm", "song", "sortBy", "like")))).containsExactly(a, b, c, d);
    }

    @Test
    @DisplayName("[NB5] 검색 결과도 페이지 계약(size·page·totalElements·totalPages·sort)과 좋아요 여부를 유지한다")
    void search_keepsPageAndLikeContract() throws Exception {
        JsonNode page0 = getFilterWith(Map.of("searchTerm", "song", "sortBy", "latest", "size", "3", "page", "0"));
        JsonNode page1 = getFilterWith(Map.of("searchTerm", "song", "sortBy", "latest", "size", "3", "page", "1"));
        assertThat(uuidList(page0)).containsExactly(d, c, b);
        assertThat(uuidList(page1)).containsExactly(a);
        assertThat(page0.path("totalElements").asLong()).isEqualTo(4);
        assertThat(page0.path("totalPages").asInt()).isEqualTo(2);
        assertThat(page0.path("sort").path("sorted").asBoolean()).isTrue();

        Map<UUID, Boolean> liked = new HashMap<>();
        for (JsonNode el : getFilterWith(Map.of("searchTerm", "song", "size", ONE_PAGE)).path("content")) {
            liked.put(UUID.fromString(el.path("musicUuid").asText()), el.path("likedByUser").asBoolean());
        }
        assertThat(liked).containsOnly(Map.entry(a, true), Map.entry(b, false), Map.entry(c, true), Map.entry(d, false));
    }

    @Test
    @DisplayName("[NB5] 검색어로도 마감 곡은 나오지 않는다")
    void search_excludesEndedMusic() throws Exception {
        assertThat(getFilterWith(Map.of("searchTerm", "ended")).path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("[NB5] 공백뿐인 검색어는 검색하지 않은 것과 같고, 대소문자는 구분하지 않는다")
    void search_blankTermAndCaseInsensitivity() throws Exception {
        assertThat(getFilterWith(Map.of("searchTerm", "   ")).path("totalElements").asLong()).isEqualTo(ONGOING_COUNT);
        assertThat(uuidSet(getFilterWith(Map.of("searchTerm", "ALPHA SONG")))).containsExactly(a);
    }

    @Test
    @DisplayName("[NB5] 검색어의 % · _ 는 와일드카드가 아니라 문자 그대로다")
    void search_escapesLikeWildcards() throws Exception {
        assertThat(getFilterWith(Map.of("searchTerm", "%")).path("totalElements").asLong()).isZero();
        assertThat(getFilterWith(Map.of("searchTerm", "_")).path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("[NB5] 현재 계약: 상세 설명(details)은 검색 대상이 아니다 — 결정 1 에 따라 Phase 3 검색 엔진 경로에서 신규 대상이 된다")
    void search_doesNotMatchDetailsInCurrentContract() throws Exception {
        assertThat(getFilterWith(Map.of("searchTerm", "qq-details-only-qq")).path("totalElements").asLong()).isZero();
    }

    // --- [NB5] BPM·키 필터 ---

    @Test
    @DisplayName("[NB5] BPM 포함 범위 필터 — 값 없는 곡(C)은 제외, 한쪽 경계만도 가능")
    void bpmRangeFilter() throws Exception {
        assertThat(uuidSet(getFilterWith(Map.of("bpmMin", "88", "bpmMax", "95", "size", ONE_PAGE)))).containsExactlyInAnyOrder(a, d, f);
        assertThat(uuidSet(getFilterWith(Map.of("bpmMin", "120", "size", ONE_PAGE)))).containsExactlyInAnyOrder(b, e);
        assertThat(uuidSet(getFilterWith(Map.of("bpmMax", "89", "size", ONE_PAGE)))).containsExactly(f);
        assertThat(uuidSet(getFilterWith(Map.of("bpmMin", "90", "bpmMax", "90", "size", ONE_PAGE)))).containsExactly(a);
    }

    @Test
    @DisplayName("[NB5] 키 정확 일치 필터 — 영문 표기와 enum 이름이 같은 결과, 값 없는 곡은 제외")
    void musicalKeyFilter() throws Exception {
        for (String key : List.of("Am", "A minor", "a min", "A_MINOR")) {
            assertThat(uuidSet(getFilterWith(Map.of("musicalKey", key, "size", ONE_PAGE)))).as(key).containsExactlyInAnyOrder(a, d);
        }
        assertThat(uuidSet(getFilterWith(Map.of("musicalKey", "C", "size", ONE_PAGE)))).containsExactly(b);
        assertThat(uuidSet(getFilterWith(Map.of("musicalKey", "Gbm", "size", ONE_PAGE)))).containsExactly(e);
    }

    @Test
    @DisplayName("[NB5] BPM·키 필터는 검색어·가격·정렬과 결합된다")
    void bpmAndKeyCombineWithSearchAndSort() throws Exception {
        assertThat(uuidList(getFilterWith(Map.of("searchTerm", "song", "musicalKey", "Am", "sortBy", "latest")))).containsExactly(d, a);
        assertThat(uuidSet(getFilterWith(Map.of("searchTerm", "song", "musicalKey", "Am", "bpmMax", "92")))).containsExactly(a);
        assertThat(uuidSet(getFilterWith(Map.of("bpmMin", "88", "maxPrice", "30000", "size", ONE_PAGE)))).containsExactlyInAnyOrder(d, e, f);
    }

    @Test
    @DisplayName("[NB5] 잘못된 BPM·키 필터는 조용히 무시하지 않고 400 으로 거부한다")
    void invalidAttributeFiltersAreRejected() throws Exception {
        assertThat(getFilterExpectingBadRequest(Map.of("bpmMin", "39"))).contains("bpmMin");
        assertThat(getFilterExpectingBadRequest(Map.of("bpmMax", "251"))).contains("bpmMax");
        assertThat(getFilterExpectingBadRequest(Map.of("bpmMin", "100", "bpmMax", "90"))).contains("bpmMin");
        assertThat(getFilterExpectingBadRequest(Map.of("musicalKey", "AM"))).contains("모호");
        assertThat(getFilterExpectingBadRequest(Map.of("musicalKey", "라단조"))).contains("musicalKey");
        mockMvc.perform(get(FILTER).param("bpmMin", "90.5")).andExpect(status().isBadRequest());
        mockMvc.perform(get(FILTER).param("bpmMin", "fast")).andExpect(status().isBadRequest());
    }
}
