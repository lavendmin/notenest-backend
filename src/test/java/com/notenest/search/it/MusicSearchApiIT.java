package com.notenest.search.it;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
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
import com.notenest.search.MusicSearchReindexService;
import com.notenest.service.BidServiceImpl;
import com.notenest.storage.FakeObjectStorage;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
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
 * [NB5] 검색어 경로(GET /api/music/filter?searchTerm=)를 실제 Elasticsearch(+Nori)로 끝까지 검증한다.
 *
 * Phase 1 에서 "검색 엔진으로 옮길 때 유지해야 할 계약"으로 남긴 테스트를 이 경로로 옮겼다 — 필터 AND 결합, 명시 정렬,
 * 페이지·좋아요, 마감 제외, 공백 검색어, 대소문자, 와일드카드 문자, BPM·키 결합, 응답 필드·커버 URL.
 * 검색 의미가 의도적으로 바뀐 곳: 기본 정렬은 관련도(정확 제목·판매자 우선), 상세 설명도 검색 대상(결정 1).
 *
 * 인프라: 로컬 MariaDB(3311)의 전용 DB notenest_es_it(create-drop) + Testcontainers 로 띄운 새 Elasticsearch.
 * 매 테스트 전에 픽스처를 만들고 MariaDB 전체를 다시 색인한다(MusicSearchReindexService).
 */
@Tag("es-integration")
@SpringBootTest(properties = {
        "spring.jwt.secret=test-secret-key-for-notenest-builds",
        "notenest.scheduling.enabled=false",
        "spring.datasource.url=jdbc:mariadb://localhost:3311/notenest_es_it?createDatabaseIfNotExist=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "notenest.search.index=notenest-music-api-it"
})
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(username = "bidder@test.local")
class MusicSearchApiIT {

    @DynamicPropertySource
    static void elasticsearch(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", ElasticsearchTestContainer::url);
    }

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

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MusicRepository musicRepository;
    @Autowired private LikeRepository likeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private BidRepository bidRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private MusicSearchReindexService reindexService;
    @Autowired private ElasticsearchClient elasticsearchClient;
    @Value("${notenest.search.index}") private String index;

    @MockBean private BidServiceImpl bidService;

    private UUID a, b, c, d, e, f, g;

    @BeforeEach
    void createFixturesAndReindex() {
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

            // MusicFilterContractTest 와 같은 픽스처(제목·작곡가·장르·태그·가격·좋아요·생성 시각·BPM·키)
            a = saveMusic("alpha song", composerA, "POP", "#seed", null, 10000L, 90000L, 5, 60, 0, 90, MusicalKey.A_MINOR, null);
            b = saveMusic("beta song", composerA, "POP", "#seed", null, 20000L, 90000L, 3, 50, 0, 120, MusicalKey.C_MAJOR, null);
            c = saveMusic("gamma song", composerB, "POP", "#seed", null, 31000L, null, 1, 40, 0, null, null, null);
            d = saveMusic("delta song", composerB, "POP", "#seed", null, 29000L, null, 0, 30, 0, 95, MusicalKey.A_MINOR, null);
            e = saveMusic("epsilon track", composerA, "JAZZ", "#jazzy,#only", "qq-unique-subtitle-qq", 11000L, null, 2, 20, 0,
                    140, MusicalKey.F_SHARP_MINOR, "qq-details-only-qq");
            f = saveMusic("zeta track", composerB, "POP", "#seed", null, 10000L, 12000L, 4, 10, 0, 88, null, null);
            g = saveMusic("ended song", composerA, "POP", "#seed", null, 10000L, 11000L, 9, 5, 1, 90, MusicalKey.A_MINOR, null);

            like(bidder, a);
            like(bidder, c);
        });
        assertThat(reindexService.rebuildFromDatabase()).isEqualTo(7);
    }

    // --- 의도한 변화: 관련도순 기본 정렬, 상세 설명 검색 ---

    @Test
    @DisplayName("sortBy 생략 시 관련도순 — 정확 제목이 1위, 정확 판매자의 곡이 판매자명 부분 일치보다 위")
    void defaultRelevance_exactTitleAndSellerFirst() throws Exception {
        JsonNode root = search(Map.of("searchTerm", "alpha song"));
        assertThat(uuidList(root).get(0)).isEqualTo(a);
        assertThat(root.path("sort").path("sorted").asBoolean()).isTrue();

        List<UUID> sellerHits = uuidList(search(Map.of("searchTerm", "composer-b", "size", "10")));
        assertThat(Set.copyOf(sellerHits.subList(0, 3))).containsExactlyInAnyOrder(c, d, f);
    }

    @Test
    @DisplayName("상세 설명도 검색 대상이다(결정 1 — LIKE 계약에서 의도적으로 바뀐 점)")
    void detailsAreSearchable() throws Exception {
        assertThat(uuidSet(search(Map.of("searchTerm", "qq-details-only-qq")))).containsExactly(e);
    }

    // --- Phase 1 에서 옮긴 계약 ---

    @Test
    @DisplayName("검색어 + 가격·장르(대소문자 무시)·해시태그(태그별 부분 일치 AND) 필터는 AND 로 결합된다")
    void combinesWithFilters() throws Exception {
        assertThat(uuidSet(search(Map.of("searchTerm", "song", "maxPrice", "30000")))).containsExactly(d);
        assertThat(uuidSet(search(Map.of("searchTerm", "track", "majorGenre", "POP")))).containsExactly(f);
        assertThat(uuidSet(search(Map.of("searchTerm", "track", "majorGenre", "pop")))).containsExactly(f);
        assertThat(uuidSet(search(Map.of("searchTerm", "track", "hashtag", "#jazzy,#only")))).containsExactly(e);
        assertThat(uuidSet(search(Map.of("searchTerm", "song", "hashtag", "#see")))).containsExactlyInAnyOrder(a, b, c, d);
    }

    @Test
    @DisplayName("명시한 정렬(최신·가격·좋아요)이 검색어 경로에서도 그대로 적용된다")
    void honorsExplicitSort() throws Exception {
        assertThat(uuidList(search(Map.of("searchTerm", "song", "sortBy", "latest")))).containsExactly(d, c, b, a);
        assertThat(uuidList(search(Map.of("searchTerm", "song", "sortBy", "price")))).containsExactly(b, a, c, d);
        assertThat(uuidList(search(Map.of("searchTerm", "song", "sortBy", "like")))).containsExactly(a, b, c, d);
    }

    @Test
    @DisplayName("페이지 계약(size·page·totalElements·totalPages)과 좋아요 여부를 유지한다")
    void keepsPageAndLikeContract() throws Exception {
        JsonNode page0 = search(Map.of("searchTerm", "song", "sortBy", "latest", "size", "3", "page", "0"));
        JsonNode page1 = search(Map.of("searchTerm", "song", "sortBy", "latest", "size", "3", "page", "1"));
        assertThat(uuidList(page0)).containsExactly(d, c, b);
        assertThat(uuidList(page1)).containsExactly(a);
        assertThat(page0.path("totalElements").asLong()).isEqualTo(4);
        assertThat(page0.path("totalPages").asInt()).isEqualTo(2);
        assertThat(page0.path("sort").path("sorted").asBoolean()).isTrue();

        Map<UUID, Boolean> liked = new HashMap<>();
        for (JsonNode el : search(Map.of("searchTerm", "song")).path("content")) {
            liked.put(UUID.fromString(el.path("musicUuid").asText()), el.path("likedByUser").asBoolean());
        }
        assertThat(liked).containsOnly(Map.entry(a, true), Map.entry(b, false), Map.entry(c, true), Map.entry(d, false));
    }

    @Test
    @DisplayName("응답 필드·커버 URL(자기 커버 키)·audio 부재 계약을 유지하고 raw 키·점수는 노출하지 않는다")
    void keepsResponseContract() throws Exception {
        JsonNode first = search(Map.of("searchTerm", "alpha song")).path("content").get(0);
        assertThat(first.path("musicUuid").asText()).isEqualTo(a.toString());
        assertThat(first.path("title").asText()).isEqualTo("alpha song");
        assertThat(first.path("userNickName").asText()).isEqualTo("composer-a");
        assertThat(first.path("startingPrice").asLong()).isEqualTo(10000L);
        assertThat(first.path("currentHighestBid").asLong()).isEqualTo(90000L);
        assertThat(first.hasNonNull("auctionEndTime")).isTrue();
        assertThat(first.path("likeCount").asInt()).isEqualTo(5);
        assertThat(first.path("coverUrl").asText()).contains(coverKey(a, "alpha song"));
        assertThat(first.has("coverObjectKey")).isFalse();
        assertThat(first.has("audio")).isFalse();
        assertThat(first.has("score")).isFalse();
        assertThat(first.has("_score")).isFalse();
    }

    @Test
    @DisplayName("마감 곡 제외, 공백 검색어는 기존 목록, 대소문자 무시, % · _ 는 문자 그대로")
    void endedBlankCaseAndWildcards() throws Exception {
        assertThat(search(Map.of("searchTerm", "ended")).path("totalElements").asLong()).isZero();
        assertThat(search(Map.of("searchTerm", "   ")).path("totalElements").asLong()).isEqualTo(6);
        assertThat(uuidList(search(Map.of("searchTerm", "ALPHA SONG"))).get(0)).isEqualTo(a);
        assertThat(search(Map.of("searchTerm", "%")).path("totalElements").asLong()).isZero();
        assertThat(search(Map.of("searchTerm", "_")).path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("BPM·키 필터가 검색어·정렬과 결합된다(값 없는 곡 제외)")
    void bpmAndKeyCombineWithSearch() throws Exception {
        assertThat(uuidList(search(Map.of("searchTerm", "song", "musicalKey", "Am", "sortBy", "latest")))).containsExactly(d, a);
        assertThat(uuidSet(search(Map.of("searchTerm", "song", "musicalKey", "A_MINOR", "bpmMax", "92")))).containsExactly(a);
        assertThat(uuidSet(search(Map.of("searchTerm", "song", "bpmMin", "100")))).containsExactly(b);
    }

    @Test
    @DisplayName("검색 결과 10,000번째를 넘는 페이지는 400")
    void deepPageIsRejected() throws Exception {
        String body = perform(Map.of("searchTerm", "song", "page", "1000", "size", "10"), status().isBadRequest());
        assertThat(body).contains("10000");
    }

    @Test
    @DisplayName("색인이 없으면 검색어 요청은 503, 검색어 없는 목록은 200")
    void missingIndexIs503WhileListWorks() throws Exception {
        elasticsearchClient.indices().delete(r -> r.index(index));
        assertThat(perform(Map.of("searchTerm", "song"), status().isServiceUnavailable())).contains("검색");
        assertThat(search(Map.of()).path("totalElements").asLong()).isEqualTo(6);
    }

    // --- 헬퍼 ---

    private JsonNode search(Map<String, String> params) throws Exception {
        return objectMapper.readTree(perform(params, status().isOk()));
    }

    private String perform(Map<String, String> params, ResultMatcher expected) throws Exception {
        var req = get(FILTER).with(r -> { r.setRemoteUser(BIDDER); return r; });
        for (var entry : params.entrySet()) req = req.param(entry.getKey(), entry.getValue());
        return mockMvc.perform(req).andExpect(expected).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<UUID> uuidList(JsonNode root) {
        return root.path("content").findValues("musicUuid").stream()
                .map(n -> UUID.fromString(n.asText())).collect(Collectors.toList());
    }

    private static Set<UUID> uuidSet(JsonNode root) {
        return Set.copyOf(uuidList(root));
    }

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

    private UUID saveMusic(String title, User owner, String genre, String hashtag, String subtitle, Long startingPrice,
                           Long highestBid, int likeCount, int createdMinutesAgo, int status, Integer bpm, MusicalKey key,
                           String details) {
        Music music = new Music();
        music.setTitle(title);
        music.setSubtitle(subtitle);
        music.setDetails(details);
        music.setUser(owner);
        music.setMajorGenre(genre);
        music.setHashtag(hashtag);
        music.setStartingPrice(startingPrice);
        music.setCurrentHighestBid(highestBid);
        music.setLikeCount(likeCount);
        music.setStatus(status);
        music.setBpm(bpm);
        music.setMusicalKey(key);
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
}
