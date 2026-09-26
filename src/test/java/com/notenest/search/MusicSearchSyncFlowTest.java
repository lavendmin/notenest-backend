package com.notenest.search;

import com.notenest.domain.Music;
import com.notenest.domain.User;
import com.notenest.jwt.JWTUtil;
import com.notenest.repository.BidRepository;
import com.notenest.repository.LikeRepository;
import com.notenest.repository.MusicRepository;
import com.notenest.repository.PaymentRepository;
import com.notenest.repository.UserRepository;
import com.notenest.service.BidServiceImpl;
import com.notenest.service.EmailService;
import com.notenest.storage.FakeObjectStorage;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [NB5 Phase 4] 곡 변경 → 검색 문서 동기화와 대조 복구 — Elasticsearch 없이 실패 주입 fake 색인으로 검증한다.
 *
 *  - 변경 경로: 등록·수정(BPM·키)·삭제(HTTP), 입찰 현재가·좋아요(HTTP — 실제 요청처럼 OSIV 로 곡 변경이 반영된다), 경매 마감(스케줄러 메서드)
 *  - 실행 시점 계약: 트랜잭션 안에서 발행 → 커밋 후 실행, 롤백 → 실행 안 함, 트랜잭션 없음 → 즉시 실행
 *  - 실패: 재시도 후 성공, 재시도 소진 시 쓰기 요청은 성공하고 실패만 기록 → 대조로 복구
 *  - 대조: 누락·불일치·잔존을 한 번에 복구하고, 두 번째 대조의 변경 건수는 0
 *
 * 동기화는 호출 스레드에서 바로 돌린다(notenest.search.sync.async=false) — 순서·시점을 결정적으로 검사하기 위해서다.
 * 비동기 쓰기 스레드와 실제 Elasticsearch 는 MusicSearchSyncIT(nb5IntegrationTest)가 검증한다.
 */
@SpringBootTest(properties = {
        "spring.jwt.secret=test-secret-key-for-notenest-builds",
        "notenest.scheduling.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:search-sync;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "notenest.search.sync.async=false",
        "notenest.search.sync.backoff-millis=0"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MusicSearchSyncFlowTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    private static final byte[] MP3 = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, 9, 9};

    @TestConfiguration
    static class FakesConfig {
        @Bean
        @Primary
        FakeObjectStorage fakeObjectStorage() {
            return new FakeObjectStorage();
        }

        @Bean
        @Primary
        FakeMusicSearchIndex fakeMusicSearchIndex() {
            return new FakeMusicSearchIndex();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JWTUtil jwtUtil;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private FakeObjectStorage storage;
    @Autowired private FakeMusicSearchIndex index;
    @Autowired private UserRepository userRepository;
    @Autowired private MusicRepository musicRepository;
    @Autowired private BidRepository bidRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private LikeRepository likeRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private MusicSearchEvents events;
    @Autowired private MusicSearchSynchronizer synchronizer;
    @Autowired private MusicSearchReconciler reconciler;
    @Autowired private SearchSyncRecorder recorder;
    @Autowired private BidServiceImpl bidService;

    @MockBean private EmailService emailService;

    private User composer;
    private User bidder;

    @BeforeEach
    void reset() {
        storage.reset();
        likeRepository.deleteAll();
        paymentRepository.deleteAll();
        bidRepository.deleteAll();
        musicRepository.deleteAll();
        userRepository.deleteAll();
        index.reset();
        composer = saveUser("composer@test.local", "composer");
        bidder = saveUser("bidder@test.local", "bidder");
    }

    // --- 변경 경로 ---

    @Test
    @DisplayName("등록: 커밋된 곡이 그대로 색인된다(문서 = DB 에서 다시 만든 문서, sourceHash 포함)")
    void createSyncs() throws Exception {
        UUID id = createSong("봄비", 76, "Eb");
        assertIndexedAsInDb(id);
        assertThat(index.get(id.toString()).bpm()).isEqualTo(76);
        assertThat(index.get(id.toString()).musicalKey()).isEqualTo("E_FLAT_MAJOR");
    }

    @Test
    @DisplayName("수정: 첫 입찰 뒤 BPM·키를 바꿔도 색인이 따라가고 sourceHash 가 바뀐다")
    void updateSyncs() throws Exception {
        UUID id = createSong("봄비", 76, "Eb");
        bid(id, 20_000L);
        String before = index.get(id.toString()).sourceHash();

        mockMvc.perform(update(id, "{\"title\":\"봄비 (리마스터)\",\"bpm\":80,\"musicalKey\":\"C minor\"}")).andExpect(status().isOk());

        assertIndexedAsInDb(id);
        MusicSearchDocument doc = index.get(id.toString());
        assertThat(doc.title()).isEqualTo("봄비 (리마스터)");
        assertThat(doc.bpm()).isEqualTo(80);
        assertThat(doc.musicalKey()).isEqualTo("C_MINOR");
        assertThat(doc.sourceHash()).isNotEqualTo(before);
    }

    @Test
    @DisplayName("삭제: 곡이 지워지면 문서도 지워진다")
    void deleteSyncs() throws Exception {
        UUID id = createSong("봄비", 76, "Eb");
        mockMvc.perform(delete("/api/music/{id}", id).header("Authorization", bearer(composer))).andExpect(status().isOk());
        assertThat(index.get(id.toString())).isNull();
    }

    @Test
    @DisplayName("입찰: 현재가와 가격 필터 값이 바뀐다")
    void bidSyncs() throws Exception {
        UUID id = createSong("봄비", 76, "Eb");
        bid(id, 25_000L);
        assertIndexedAsInDb(id);
        assertThat(index.get(id.toString()).currentHighestBid()).isEqualTo(25_000L);
        assertThat(index.get(id.toString()).price()).isEqualTo(25_000L);
    }

    @Test
    @DisplayName("좋아요·취소: 좋아요 수가 따라간다(메서드 트랜잭션 커밋 후)")
    void likeSyncs() throws Exception {
        UUID id = createSong("봄비", 76, "Eb");
        mockMvc.perform(post("/api/music/{id}/like", id).header("Authorization", bearer(bidder))).andExpect(status().isOk());
        assertThat(index.get(id.toString()).likeCount()).isEqualTo(1);
        mockMvc.perform(post("/api/music/{id}/like", id).header("Authorization", bearer(bidder))).andExpect(status().isOk());
        assertThat(index.get(id.toString()).likeCount()).isZero();
        assertIndexedAsInDb(id);
    }

    @Test
    @DisplayName("경매 마감: 스케줄러 사이클 트랜잭션이 커밋된 뒤 status=1 이 반영된다")
    void auctionEndSyncs() throws Exception {
        UUID id = createSong("봄비", 76, "Eb");
        transactionTemplate.executeWithoutResult(tx -> entityManager
                .createQuery("update Music m set m.auctionEndTime = :t where m.musicUuid = :id")
                .setParameter("t", LocalDateTime.now().minusMinutes(1)).setParameter("id", id).executeUpdate());

        bidService.checkAuctionEnd();

        assertThat(index.get(id.toString()).status()).isEqualTo(1);
        assertIndexedAsInDb(id);
    }

    // --- 실행 시점 계약 ---

    @Test
    @DisplayName("트랜잭션 안에서 발행하면 커밋 전에는 반영하지 않고 커밋 후에 커밋된 값으로 반영한다")
    void inTransaction_runsAfterCommit() throws Exception {
        UUID id = createSong("봄비", 76, "Eb");
        index.reset();

        transactionTemplate.executeWithoutResult(tx -> {
            rename(id, "커밋 후 제목");
            events.changed(id, "test");
            assertThat(index.get(id.toString())).as("커밋 전").isNull();
        });

        assertThat(index.get(id.toString()).title()).isEqualTo("커밋 후 제목");
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 동기화하지 않는다")
    void rollback_doesNotSync() throws Exception {
        UUID id = createSong("봄비", 76, "Eb");
        index.reset();

        transactionTemplate.executeWithoutResult(tx -> {
            rename(id, "롤백될 제목");
            events.changed(id, "test");
            tx.setRollbackOnly();
        });

        assertThat(index.get(id.toString())).isNull();
        assertThat(index.writeCalls()).isZero();
    }

    @Test
    @DisplayName("트랜잭션 없이 발행하면(저장이 이미 커밋된 뒤) 즉시 실행한다")
    void withoutTransaction_runsImmediately() throws Exception {
        UUID id = createSong("봄비", 76, "Eb");
        index.reset();
        transactionTemplate.executeWithoutResult(tx -> rename(id, "먼저 커밋된 제목"));

        events.changed(id, "test");

        assertThat(index.get(id.toString()).title()).isEqualTo("먼저 커밋된 제목");
    }

    // --- 재시도·실패 ---

    @Test
    @DisplayName("색인 실패 2회 후 3번째에 성공하면 문서가 반영되고 '재시도 후 복구'로 기록된다")
    void retryThenSucceed() throws Exception {
        long recoveredBefore = recorder.snapshot().recoveredAfterRetry();
        index.failNextWrites(2);

        UUID id = createSong("봄비", 76, "Eb");

        assertIndexedAsInDb(id);
        assertThat(recorder.snapshot().recoveredAfterRetry()).isEqualTo(recoveredBefore + 1);
    }

    @Test
    @DisplayName("재시도(3회)를 모두 실패해도 등록은 201 로 성공하고, 실패만 기록된다 → 대조가 누락을 복구하고 2차 대조는 0건")
    void retriesExhausted_writeStillSucceeds_reconcileRecovers() throws Exception {
        long failedBefore = recorder.snapshot().failed();
        index.failNextWrites(3);

        UUID id = createSong("봄비", 76, "Eb"); // 201 확인 포함

        assertThat(musicRepository.findById(id)).as("DB 저장은 성공").isPresent();
        assertThat(index.get(id.toString())).as("색인 누락").isNull();
        SearchSyncRecorder.Snapshot snapshot = recorder.snapshot();
        assertThat(snapshot.failed()).isEqualTo(failedBefore + 1);
        assertThat(snapshot.recentFailures().get(snapshot.recentFailures().size() - 1).musicUuid()).isEqualTo(id);

        MusicSearchReconciler.Result first = reconciler.reconcile();
        assertThat(first.missing()).isEqualTo(1);
        assertIndexedAsInDb(id);
        assertThat(reconciler.reconcile().totalChanges()).isZero();
    }

    // --- 대조 ---

    @Test
    @DisplayName("누락(동기화 유실)·불일치(이벤트 없는 DB 변경)·잔존(이벤트 없는 삭제)을 한 번에 복구하고, 2차 대조는 변경 0건")
    void reconcileRepairsMissingChangedAndStale() throws Exception {
        UUID lost = createSong("유실될 곡", 90, "Am");
        UUID drifted = createSong("바뀔 곡", 100, "C");
        UUID removed = createSong("지워질 곡", 120, "G");
        UUID untouched = createSong("그대로인 곡", 128, "Fm");

        index.removeRaw(lost.toString());                                   // 커밋 후 프로세스 중단으로 동기화 유실
        transactionTemplate.executeWithoutResult(tx -> rename(drifted, "이벤트 없이 바뀐 제목")); // 이벤트 없는 변경
        transactionTemplate.executeWithoutResult(tx -> musicRepository.deleteById(removed));  // 이벤트 없는 삭제

        MusicSearchReconciler.Result first = reconciler.reconcile();
        assertThat(List.of(first.missing(), first.changed(), first.stale())).containsExactly(1, 1, 1);
        assertThat(first.dbDocuments()).isEqualTo(3);
        assertIndexedAsInDb(lost);
        assertIndexedAsInDb(drifted);
        assertIndexedAsInDb(untouched);
        assertThat(index.get(removed.toString())).isNull();
        assertThat(index.all()).hasSize(3);

        MusicSearchReconciler.Result second = reconciler.reconcile();
        assertThat(second.totalChanges()).isZero();
        assertThat(second.indexedBefore()).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 문서의 중복 동기화는 결과가 같고(멱등) 대조가 고칠 것이 없다")
    void duplicateUpsertIsIdempotent() throws Exception {
        UUID id = createSong("봄비", 76, "Eb");
        MusicSearchDocument once = index.get(id.toString());

        synchronizer.syncOnce(id);
        synchronizer.syncOnce(id);
        events.changed(id, "duplicate");

        assertThat(index.get(id.toString())).isEqualTo(once);
        assertThat(reconciler.reconcile().totalChanges()).isZero();
    }

    @Test
    @DisplayName("색인이 통째로 비어도 대조가 전부 다시 채운다")
    void reconcileRebuildsLostIndex() throws Exception {
        UUID a = createSong("첫 곡", 90, "Am");
        UUID b = createSong("둘째 곡", 100, "C");
        index.recreate();

        MusicSearchReconciler.Result result = reconciler.reconcile();

        assertThat(result.missing()).isEqualTo(2);
        assertIndexedAsInDb(a);
        assertIndexedAsInDb(b);
    }

    // --- 헬퍼 ---

    private void assertIndexedAsInDb(UUID id) {
        MusicSearchDocument expected = MusicSearchDocument.from(musicRepository.findSearchSource(id).orElseThrow());
        assertThat(index.get(id.toString())).as("색인 문서 = DB 에서 다시 만든 문서").isEqualTo(expected);
    }

    private void rename(UUID id, String title) {
        entityManager.createQuery("update Music m set m.title = :t where m.musicUuid = :id")
                .setParameter("t", title).setParameter("id", id).executeUpdate();
    }

    private UUID createSong(String title, int bpm, String key) throws Exception {
        String json = "{\"title\":\"" + title + "\",\"startingPrice\":10000,\"majorGenre\":\"balad\",\"musicPeriod\":3,"
                + "\"showAllBids\":true,\"bpm\":" + bpm + ",\"musicalKey\":\"" + key + "\"}";
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

    private MockMultipartHttpServletRequestBuilder update(UUID id, String json) {
        MockMultipartHttpServletRequestBuilder request = multipart(HttpMethod.PUT, "/api/music/{id}", id);
        request.file(new MockMultipartFile("music", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes(StandardCharsets.UTF_8)));
        request.header("Authorization", bearer(composer));
        return request;
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
}
