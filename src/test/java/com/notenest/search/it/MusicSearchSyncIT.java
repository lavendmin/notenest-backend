package com.notenest.search.it;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notenest.domain.Music;
import com.notenest.domain.User;
import com.notenest.jwt.JWTUtil;
import com.notenest.repository.BidRepository;
import com.notenest.repository.LikeRepository;
import com.notenest.repository.MusicRepository;
import com.notenest.repository.PaymentRepository;
import com.notenest.repository.UserRepository;
import com.notenest.search.MusicSearchIndex;
import com.notenest.search.MusicSearchReconciler;
import com.notenest.search.MusicSearchSynchronizer;
import com.notenest.search.SearchSyncRecorder;
import com.notenest.service.EmailService;
import com.notenest.storage.FakeObjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [NB5 Phase 4] 실제 Elasticsearch + 비동기 쓰기 스레드로 동기화·누락 창·대조 복구를 끝까지 검증한다.
 *
 *  - 쓰기(등록·입찰·삭제) 후 검색 API 에 반영되기까지 기다려 확인한다(eventual consistency, 반영 시간 기록).
 *  - 쓰기 거부(write block): 등록은 201, 동기화는 재시도 소진 후 실패 기록 → 문서가 확정적으로 누락 → 대조가 복구, 2차 0건.
 *  - 응답 없음(컨테이너 일시정지): 등록은 바로 201, 동기화는 타임아웃으로 '미확인' 기록. 타임아웃 전에 전달된 요청은 ES 가 되살아난 뒤
 *    늦게 반영될 수 있다(타임아웃 ≠ 실패). 어느 쪽이든 대조 후 색인은 원본과 같고 2차 대조는 0건이다.
 * 결과는 build/nb5-it/phase4-sync-it.txt 에 남긴다.
 */
@Tag("es-integration")
@SpringBootTest(properties = {
        "spring.jwt.secret=test-secret-key-for-notenest-builds",
        "notenest.scheduling.enabled=false",
        "spring.datasource.url=jdbc:mariadb://localhost:3311/notenest_es_it?createDatabaseIfNotExist=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "notenest.search.index=notenest-music-sync-it",
        "notenest.search.sync.async=true",
        "notenest.search.sync.max-attempts=3",
        "notenest.search.sync.backoff-millis=200"
})
@AutoConfigureMockMvc
class MusicSearchSyncIT {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    private static final byte[] MP3 = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, 9, 9};
    private static final Duration VISIBLE_WITHIN = Duration.ofSeconds(15);

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

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JWTUtil jwtUtil;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private MusicRepository musicRepository;
    @Autowired private BidRepository bidRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private LikeRepository likeRepository;
    @Autowired private MusicSearchIndex index;
    @Autowired private MusicSearchSynchronizer synchronizer;
    @Autowired private MusicSearchReconciler reconciler;
    @Autowired private SearchSyncRecorder recorder;
    @Autowired private ElasticsearchClient elasticsearchClient;

    @MockBean private EmailService emailService;

    private User composer;
    private User bidder;
    private final List<String> log = new ArrayList<>();

    @BeforeEach
    void reset() throws Exception {
        likeRepository.deleteAll();
        paymentRepository.deleteAll();
        bidRepository.deleteAll();
        musicRepository.deleteAll();
        userRepository.deleteAll();
        synchronizer.submit(() -> {
            index.recreate();
            return null;
        }).get(30, TimeUnit.SECONDS);
        composer = saveUser("composer@test.local", "composer");
        bidder = saveUser("bidder@test.local", "bidder");
    }

    @AfterEach
    void writeLog() throws Exception {
        Path out = Path.of("build", "nb5-it", "phase4-sync-it.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, String.join("\n", log) + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    @Test
    @DisplayName("등록·입찰·삭제가 비동기 쓰기 스레드를 거쳐 실제 검색 결과에 반영된다")
    void writesBecomeSearchable() throws Exception {
        long t0 = System.nanoTime();
        UUID id = createSong("여름밤 동기화");
        await().atMost(VISIBLE_WITHIN).untilAsserted(() -> assertThat(searchIds("여름밤 동기화")).contains(id));
        log.add("[sync] create → searchable in " + millisSince(t0) + " ms");

        long t1 = System.nanoTime();
        bid(id, 25_000L);
        await().atMost(VISIBLE_WITHIN).untilAsserted(() ->
                assertThat(firstHit("여름밤 동기화").path("currentHighestBid").asLong()).isEqualTo(25_000L));
        log.add("[sync] bid → price visible in " + millisSince(t1) + " ms");

        UUID other = createSong("지울 노래");
        await().atMost(VISIBLE_WITHIN).untilAsserted(() -> assertThat(searchIds("지울 노래")).contains(other));
        long t2 = System.nanoTime();
        mockMvc.perform(delete("/api/music/{id}", other).header("Authorization", bearer(composer))).andExpect(status().isOk());
        await().atMost(VISIBLE_WITHIN).untilAsserted(() -> assertThat(searchIds("지울 노래")).doesNotContain(other));
        log.add("[sync] delete → gone in " + millisSince(t2) + " ms");
    }

    @Test
    @DisplayName("쓰기 거부(색인 write block — 디스크 flood-stage 와 같은 상태) 중 등록: 201, 재시도 소진 기록, 문서 확정 누락 → 대조 복구, 2차 0건")
    void rejectedWritesAreRecoveredByReconcile() throws Exception {
        UUID before = createSong("거부 전 노래");
        await().atMost(VISIBLE_WITHIN).untilAsserted(() -> assertThat(searchIds("거부 전 노래")).contains(before));
        long failedBefore = recorder.snapshot().failed();

        setWriteBlock(true);
        UUID lost;
        try {
            long t0 = System.nanoTime();
            lost = createSong("거부 중 등록한 노래");
            log.add("[write-block] create response: 201 in " + millisSince(t0) + " ms");
            await().atMost(Duration.ofSeconds(30)).until(() -> recorder.snapshot().failed() > failedBefore);
            SearchSyncRecorder.Failure failure = lastFailure();
            log.add("[write-block] sync gave up: attempts=" + failure.attempts() + " cause=" + failure.cause());
            assertThat(failure.musicUuid()).isEqualTo(lost);
            assertThat(failure.attempts()).isEqualTo(3);
        } finally {
            setWriteBlock(false);
        }

        assertThat(musicRepository.findById(lost)).as("DB 에는 있다").isPresent();
        index.refresh();
        assertThat(searchIds("거부 중 등록한 노래")).as("색인 확정 누락 — 검색되지 않는다").doesNotContain(lost);

        MusicSearchReconciler.Result first = synchronizer.submit(reconciler::reconcile).get(60, TimeUnit.SECONDS);
        log.add("[write-block] reconcile #1: " + first);
        assertThat(List.of(first.missing(), first.changed(), first.stale())).containsExactly(1, 0, 0);
        assertThat(searchIds("거부 중 등록한 노래")).contains(lost);

        MusicSearchReconciler.Result second = synchronizer.submit(reconciler::reconcile).get(60, TimeUnit.SECONDS);
        log.add("[write-block] reconcile #2: " + second);
        assertThat(second.totalChanges()).isZero();
    }

    @Test
    @DisplayName("응답 없음(ES 정지) 중 등록: 201 즉시, 타임아웃으로 '미확인' 기록 — 늦게 반영될 수도 있지만 대조 후에는 원본과 같고 2차 0건")
    void timedOutWritesAreAmbiguousButConverge() throws Exception {
        UUID before = createSong("정지 전 노래");
        await().atMost(VISIBLE_WITHIN).untilAsserted(() -> assertThat(searchIds("정지 전 노래")).contains(before));
        long failedBefore = recorder.snapshot().failed();

        ElasticsearchTestContainer.pause();
        UUID pending;
        try {
            long t0 = System.nanoTime();
            pending = createSong("정지 중 등록한 노래");
            long createMillis = millisSince(t0);
            log.add("[timeout] create response while ES paused: 201 in " + createMillis + " ms");
            assertThat(createMillis).as("쓰기 요청은 색인을 기다리지 않는다").isLessThan(3_000);

            long t1 = System.nanoTime();
            await().atMost(Duration.ofSeconds(40)).until(() -> recorder.snapshot().failed() > failedBefore);
            SearchSyncRecorder.Failure failure = lastFailure();
            log.add("[timeout] sync gave up after " + millisSince(t1) + " ms, attempts=" + failure.attempts()
                    + ", cause=" + failure.cause());
            assertThat(failure.musicUuid()).isEqualTo(pending);
        } finally {
            ElasticsearchTestContainer.unpause();
        }

        index.refresh();
        boolean appliedLate = searchIds("정지 중 등록한 노래").contains(pending);
        log.add("[timeout] after resume, request delivered before the timeout was applied late: " + appliedLate);

        MusicSearchReconciler.Result first = synchronizer.submit(reconciler::reconcile).get(60, TimeUnit.SECONDS);
        log.add("[timeout] reconcile #1: " + first);
        assertThat(first.stale()).isZero();
        assertThat(first.missing()).isEqualTo(appliedLate ? 0 : 1);
        assertThat(searchIds("정지 중 등록한 노래")).contains(pending);
        MusicSearchReconciler.Result second = synchronizer.submit(reconciler::reconcile).get(60, TimeUnit.SECONDS);
        log.add("[timeout] reconcile #2: " + second);
        assertThat(second.totalChanges()).isZero();
    }

    private SearchSyncRecorder.Failure lastFailure() {
        List<SearchSyncRecorder.Failure> failures = recorder.snapshot().recentFailures();
        return failures.get(failures.size() - 1);
    }

    private void setWriteBlock(boolean blocked) throws Exception {
        elasticsearchClient.indices().putSettings(p -> p.index(index.name())
                .settings(st -> st.blocks(b -> b.write(blocked))));
    }

    // --- 헬퍼 ---

    private List<UUID> searchIds(String term) throws Exception {
        List<UUID> ids = new ArrayList<>();
        search(term).path("content").forEach(n -> ids.add(UUID.fromString(n.path("musicUuid").asText())));
        return ids;
    }

    private JsonNode firstHit(String term) throws Exception {
        return search(term).path("content").get(0);
    }

    private JsonNode search(String term) throws Exception {
        String body = mockMvc.perform(get("/api/music/filter").param("searchTerm", term))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    private UUID createSong(String title) throws Exception {
        String json = "{\"title\":\"" + title + "\",\"startingPrice\":10000,\"majorGenre\":\"balad\",\"musicPeriod\":3,"
                + "\"showAllBids\":true,\"bpm\":90,\"musicalKey\":\"Am\"}";
        MockMultipartHttpServletRequestBuilder request = multipart("/api/music/create");
        request.file(new MockMultipartFile("music", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes(StandardCharsets.UTF_8)));
        request.file(new MockMultipartFile("image", "c.png", "image/png", PNG));
        request.file(new MockMultipartFile("preview", "p.mp3", "audio/mpeg", MP3));
        request.file(new MockMultipartFile("audio", "f.mp3", "audio/mpeg", MP3));
        request.header("Authorization", bearer(composer));
        mockMvc.perform(request).andExpect(status().isCreated());
        return musicRepository.findAll().stream().filter(m -> title.equals(m.getTitle())).map(Music::getMusicUuid)
                .findFirst().orElseThrow();
    }

    private void bid(UUID id, long price) throws Exception {
        mockMvc.perform(post("/api/bid/create").header("Authorization", bearer(bidder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"musicUuid\":\"" + id + "\",\"price\":" + price + ",\"password\":\"pw\"}"))
                .andExpect(status().isCreated());
    }

    private User saveUser(String email, String nickname) {
        User user = new User();
        user.setEmail(email);
        user.setNickname(nickname);
        user.setName(nickname);
        user.setPassword(passwordEncoder.encode("pw"));
        user.setRole("ROLE_USER");
        user.setEmailVerified(true);
        user.setAgreement(true);
        return userRepository.save(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtUtil.createJwt(user.getEmail(), user.getRole());
    }

    private static long millisSince(long nanoStart) {
        return (System.nanoTime() - nanoStart) / 1_000_000;
    }
}
