package com.notenest.contract;

import com.notenest.domain.Bid;
import com.notenest.domain.MediaObject;
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
import com.notenest.storage.ObjectStorage;
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
import org.springframework.data.domain.Page;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 곡 등록·수정·삭제의 객체 저장소 전환과 부분 실패 보상 계약.
 *
 * 객체 저장소와 DB 는 한 트랜잭션으로 묶이지 않는다. 실제 S3 는 원하는 순간에 실패시킬 수 없으므로
 * 실패를 주입할 수 있는 {@link FakeObjectStorage} 로 저장소를 바꿔 다음을 검증한다.
 *  - 정상: 세 파일이 music/{musicUuid}/{자산}/{assetUuid} 키로 올라가고 DB 에는 키·메타데이터만 남는다(LOB 없음)
 *  - 검증 실패: 아무것도 올리지 않는다
 *  - 업로드 중 실패: 이미 올린 파일을 지우고 곡을 저장하지 않는다
 *  - DB 저장 실패: 올린 파일 세 개를 모두 지운다
 *  - 보상 삭제마저 실패: 원래 실패를 그대로 응답하고, 남은 객체는 고아로 기록된다
 *  - 수정: 보낸 파일만 새 키로 올리고 DB 전환 성공 후 옛 객체를 지운다. 중간 실패 시 옛 객체·DB 를 보존한다.
 *    전체 데모는 첫 입찰 전까지만 교체할 수 있다.
 *  - 삭제: DB 삭제 성공 후 객체를 지운다. 정리 실패는 고아로 남되 삭제 자체는 성공한다.
 * AWS 연동(권한·private 접근·URL 만료)은 여기서 검증하지 않는다 — 실제 S3 E2E 의 몫이다.
 *
 * 인프라: H2(MySQL 모드) + MockMvc, 보안 필터 켬, {@link JWTUtil} 로 발급한 실제 토큰.
 */
@SpringBootTest(properties = {
        "spring.jwt.secret=test-secret-key-for-notenest-builds",
        // user 는 H2 예약어라 @Table(name="user") DDL/쿼리가 깨진다 → NON_KEYWORDS 로 제외.
        "spring.datasource.url=jdbc:h2:mem:music-create;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MusicStorageFlowTest {

    private static final byte[] PNG = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3);
    private static final byte[] PNG2 = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 4, 5, 6);
    private static final byte[] MP3 = bytes('I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, 9, 9);
    private static final byte[] WAV = bytes('R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'A', 'V', 'E', 7, 7, 7);

    @TestConfiguration
    static class FakeStorageConfig {
        @Bean
        @Primary
        FakeObjectStorage fakeObjectStorage() {
            return new FakeObjectStorage();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JWTUtil jwtUtil;
    @Autowired private FakeObjectStorage storage;
    @Autowired private ObjectStorage injectedStorage;
    @Autowired private UserRepository userRepository;
    @Autowired private MusicRepository musicRepository;
    @Autowired private BidRepository bidRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private LikeRepository likeRepository;

    // @Scheduled 경매 배치가 픽스처를 건드리지 않게 한다.
    @MockBean private BidServiceImpl bidService;
    @MockBean private EmailService emailService;

    private User composer;
    private User bidder;

    @BeforeEach
    void reset() {
        // 상세가 쓰는 입찰 목록 조회만 빈 페이지로 스텁한다(BidServiceImpl 은 스케줄러 차단용 목).
        when(bidService.getAllBidsByMusic(any(), any())).thenReturn(Page.empty());
        storage.reset();
        likeRepository.deleteAll();
        paymentRepository.deleteAll();
        bidRepository.deleteAll();
        musicRepository.deleteAll();
        userRepository.deleteAll();

        composer = new User();
        composer.setEmail("composer@test.local");
        composer.setNickname("composer");
        composer.setName("composer");
        composer.setPassword("x");
        composer.setRole("ROLE_USER");
        composer.setEmailVerified(true);
        composer.setAgreement(true);
        composer = userRepository.save(composer);

        bidder = new User();
        bidder.setEmail("bidder@test.local");
        bidder.setNickname("bidder");
        bidder.setName("bidder");
        bidder.setPassword("x");
        bidder.setRole("ROLE_USER");
        bidder.setEmailVerified(true);
        bidder.setAgreement(true);
        bidder = userRepository.save(bidder);
    }

    @Test
    @DisplayName("정상 등록: 세 파일이 곡 UUID 아래 자산별 키로 올라가고 DB 에는 키·메타데이터만 남는다")
    void storesThreeAssetsAndKeysOnly() throws Exception {
        // 선언 Content-Type 을 일부러 틀리게 보내도 판별한 실제 형식으로 저장한다.
        mockMvc.perform(create("valid title", file("image", "커버.png", "application/octet-stream", PNG),
                        file("preview", "preview.mp3", "audio/mpeg", MP3),
                        file("audio", "봄밤 데모.wav", "audio/mpeg", WAV)))
                .andExpect(status().isCreated());

        assertThat(injectedStorage).as("테스트에서는 fake 저장소가 주입된다").isSameAs(storage);
        List<Music> saved = musicRepository.findAll();
        assertThat(saved).hasSize(1);
        Music music = saved.get(0);
        String prefix = "music/" + music.getMusicUuid() + "/";

        assertThat(music.getCover().getObjectKey()).startsWith(prefix + "cover/");
        assertThat(music.getCover().getContentType()).isEqualTo("image/png");
        assertThat(music.getCover().getSize()).isEqualTo(PNG.length);
        assertThat(music.getCover().getOriginalName()).isEqualTo("커버.png");

        assertThat(music.getPreview().getObjectKey()).startsWith(prefix + "preview/");
        assertThat(music.getPreview().getContentType()).isEqualTo("audio/mpeg");

        assertThat(music.getFullDemo().getObjectKey()).startsWith(prefix + "full-demo/");
        assertThat(music.getFullDemo().getContentType()).isEqualTo("audio/wav");
        assertThat(music.getFullDemo().getOriginalName()).isEqualTo("봄밤 데모.wav");

        assertThat(storage.keys()).containsExactlyInAnyOrder(
                music.getCover().getObjectKey(), music.getPreview().getObjectKey(), music.getFullDemo().getObjectKey());
        assertThat(storage.bytes(music.getFullDemo().getObjectKey())).isEqualTo(WAV);

    }

    @Test
    @DisplayName("등록→목록 커버 URL→상세 미리듣기 URL→판매자 전체 데모 URL 이 모두 실제로 올라간 객체를 가리킨다")
    void uploadedObjectsAreServedThroughUrls() throws Exception {
        mockMvc.perform(validCreate("e2e title")).andExpect(status().isCreated());
        Music music = musicRepository.findAll().get(0);
        String token = "Bearer " + jwtUtil.createJwt(composer.getEmail(), composer.getRole());

        String listBody = mockMvc.perform(get("/api/music/filter"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String detailBody = mockMvc.perform(get("/api/music/{id}", music.getMusicUuid()).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String downloadBody = mockMvc.perform(get("/api/mypage/download/{id}", music.getMusicUuid()).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(listBody).contains(music.getCover().getObjectKey()).doesNotContain(music.getFullDemo().getObjectKey());
        assertThat(detailBody).contains(music.getCover().getObjectKey(), music.getPreview().getObjectKey())
                .doesNotContain(music.getFullDemo().getObjectKey());
        assertThat(downloadBody).contains(music.getFullDemo().getObjectKey(), "\"fileName\":\"e2e title.wav\"");
        assertThat(storage.keys()).contains(music.getCover().getObjectKey(), music.getPreview().getObjectKey(),
                music.getFullDemo().getObjectKey());
    }

    @Test
    @DisplayName("미리듣기 누락은 400 이고 아무것도 올리거나 저장하지 않는다")
    void missingPreviewIsRejectedBeforeUpload() throws Exception {
        mockMvc.perform(create("valid title", file("image", "c.png", "image/png", PNG),
                        null,
                        file("audio", "f.mp3", "audio/mpeg", MP3)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("미리듣기 파일이 비어 있습니다."));

        assertThat(storage.keys()).isEmpty();
        assertThat(musicRepository.count()).isZero();
    }

    @Test
    @DisplayName("형식 위반(미리듣기에 WAV)은 400 이고 앞선 파일도 올리지 않는다 — 검증을 업로드보다 먼저 한다")
    void invalidFormatIsRejectedBeforeAnyUpload() throws Exception {
        mockMvc.perform(create("valid title", file("image", "c.png", "image/png", PNG),
                        file("preview", "p.wav", "audio/mpeg", WAV),
                        file("audio", "f.mp3", "audio/mpeg", MP3)))
                .andExpect(status().isBadRequest());

        assertThat(storage.keys()).isEmpty();
        assertThat(musicRepository.count()).isZero();
    }

    @Test
    @DisplayName("세 번째(전체 데모) 업로드 실패: 503, 먼저 올린 커버·미리듣기를 지우고 곡을 저장하지 않는다")
    void uploadFailureRemovesAlreadyUploadedObjects() throws Exception {
        storage.failPutAt(3);

        mockMvc.perform(validCreate("valid title")).andExpect(status().isServiceUnavailable());

        assertThat(storage.keys()).as("고아 객체 없음").isEmpty();
        assertThat(musicRepository.count()).isZero();
    }

    @Test
    @DisplayName("업로드 후 DB 저장 실패(제목 길이 초과): 올린 세 파일을 모두 지운다")
    void dbFailureRemovesAllUploadedObjects() throws Exception {
        String tooLongTitle = "t".repeat(300); // title VARCHAR(255) — 업로드는 성공하고 INSERT 에서 실패한다

        mockMvc.perform(validCreate(tooLongTitle)).andExpect(status().isInternalServerError());

        assertThat(storage.keys()).as("고아 객체 없음").isEmpty();
        assertThat(musicRepository.count()).isZero();
    }

    @Test
    @DisplayName("보상 삭제마저 실패하면 원래 실패(503)를 그대로 응답하고, 지우지 못한 객체만 고아로 남는다")
    void compensationFailureKeepsOriginalErrorAndLeavesOnlyThatOrphan() throws Exception {
        storage.failPutAt(3).failDeleteOf("/cover/");

        mockMvc.perform(validCreate("valid title")).andExpect(status().isServiceUnavailable());

        assertThat(storage.keys()).hasSize(1).allMatch(key -> key.contains("/cover/"));
        assertThat(musicRepository.count()).isZero();
    }

    // --- 수정: 보낸 파일만 새 키로 교체, DB 전환 성공 후 옛 객체 정리 ---

    @Test
    @DisplayName("커버 교체: 새 키로 올리고 DB 를 전환한 뒤 옛 커버 객체를 지운다 — 다른 자산은 그대로")
    void replaceCoverSwapsKeyThenDeletesOldObject() throws Exception {
        Music before = createSong();

        mockMvc.perform(update(before, "{}", file("image", "new.png", "image/png", PNG2), null, null))
                .andExpect(status().isOk());

        Music after = reload(before);
        assertThat(after.getCover().getObjectKey())
                .startsWith("music/" + before.getMusicUuid() + "/cover/")
                .isNotEqualTo(before.getCover().getObjectKey());
        assertThat(storage.bytes(after.getCover().getObjectKey())).isEqualTo(PNG2);
        assertThat(storage.keys()).doesNotContain(before.getCover().getObjectKey())
                .contains(before.getPreview().getObjectKey(), before.getFullDemo().getObjectKey());
        assertThat(after.getPreview().getObjectKey()).isEqualTo(before.getPreview().getObjectKey());
        assertThat(after.getFullDemo().getObjectKey()).isEqualTo(before.getFullDemo().getObjectKey());
    }

    @Test
    @DisplayName("미리듣기가 없는 기존 곡(백필분)에 미리듣기 추가: 새 키가 생기고 커버·전체 데모 키는 그대로")
    void legacySongGainsPreview() throws Exception {
        Music legacy = saveLegacySong();

        mockMvc.perform(update(legacy, "{}", null, file("preview", "p.mp3", "audio/mpeg", MP3), null))
                .andExpect(status().isOk());

        Music after = reload(legacy);
        assertThat(after.getPreview().getObjectKey()).contains("/preview/");
        assertThat(after.getCover().getObjectKey()).isEqualTo(legacy.getCover().getObjectKey());
        assertThat(after.getFullDemo().getObjectKey()).isEqualTo(legacy.getFullDemo().getObjectKey());
    }

    @Test
    @DisplayName("첫 입찰 전에는 전체 데모를 교체할 수 있고 옛 전체 데모 객체는 지워진다")
    void replaceFullDemoBeforeFirstBid() throws Exception {
        Music before = createSong();

        mockMvc.perform(update(before, "{}", null, null, file("audio", "v2.mp3", "audio/mpeg", MP3)))
                .andExpect(status().isOk());

        Music after = reload(before);
        assertThat(after.getFullDemo().getObjectKey()).isNotEqualTo(before.getFullDemo().getObjectKey());
        assertThat(after.getFullDemo().getContentType()).isEqualTo("audio/mpeg");
        assertThat(storage.keys()).doesNotContain(before.getFullDemo().getObjectKey());
    }

    @Test
    @DisplayName("첫 입찰 발생 후 전체 데모 교체는 409 이고 아무것도 올리지 않으며 DB 도 그대로다")
    void replaceFullDemoAfterFirstBidIsRejected() throws Exception {
        Music before = createSong();
        saveBid(before, bidder);
        var keysBefore = storage.keys();

        mockMvc.perform(update(before, "{}", null, null, file("audio", "v2.mp3", "audio/mpeg", MP3)))
                .andExpect(status().isConflict());

        assertThat(storage.keys()).isEqualTo(keysBefore);
        assertThat(reload(before).getFullDemo().getObjectKey()).isEqualTo(before.getFullDemo().getObjectKey());
    }

    @Test
    @DisplayName("교체 파일 형식 위반은 400 이고 함께 보낸 정상 파일도 올리지 않는다")
    void invalidReplacementUploadsNothing() throws Exception {
        Music before = createSong();
        var keysBefore = storage.keys();

        mockMvc.perform(update(before, "{}", file("image", "c.png", "image/png", PNG2),
                        file("preview", "p.wav", "audio/mpeg", WAV), null))
                .andExpect(status().isBadRequest());

        assertThat(storage.keys()).isEqualTo(keysBefore);
        assertThat(reload(before).getCover().getObjectKey()).isEqualTo(before.getCover().getObjectKey());
    }

    @Test
    @DisplayName("교체 업로드 중 실패: 503, 새로 올린 것만 지우고 옛 객체·DB 는 그대로 — 기존 곡을 잃지 않는다")
    void replacementUploadFailureKeepsOldObjects() throws Exception {
        Music before = createSong();          // put 1~3
        var keysBefore = storage.keys();
        storage.failPutAt(5);                 // 교체: 커버(put 4) 성공, 미리듣기(put 5) 실패

        mockMvc.perform(update(before, "{}", file("image", "c.png", "image/png", PNG2),
                        file("preview", "p.mp3", "audio/mpeg", MP3), null))
                .andExpect(status().isServiceUnavailable());

        assertThat(storage.keys()).isEqualTo(keysBefore);
        Music after = reload(before);
        assertThat(after.getCover().getObjectKey()).isEqualTo(before.getCover().getObjectKey());
        assertThat(after.getPreview().getObjectKey()).isEqualTo(before.getPreview().getObjectKey());
    }

    @Test
    @DisplayName("교체 후 DB 전환 실패(제목 길이 초과): 새로 올린 객체만 지우고 옛 객체·DB 는 그대로다")
    void replacementDbFailureKeepsOldObjects() throws Exception {
        Music before = createSong();
        var keysBefore = storage.keys();

        mockMvc.perform(update(before, "{\"title\":\"" + "t".repeat(300) + "\"}",
                        file("image", "c.png", "image/png", PNG2), null, null))
                .andExpect(status().isInternalServerError());

        assertThat(storage.keys()).isEqualTo(keysBefore);
        Music after = reload(before);
        assertThat(after.getCover().getObjectKey()).isEqualTo(before.getCover().getObjectKey());
        assertThat(after.getTitle()).isEqualTo("valid title");
    }

    @Test
    @DisplayName("DB 전환 성공 후 옛 객체 정리 실패: 수정은 성공(200)하고 옛 커버만 고아로 남는다")
    void oldObjectCleanupFailureLeavesOrphanOnly() throws Exception {
        Music before = createSong();
        storage.failDeleteOf(before.getCover().getObjectKey());

        mockMvc.perform(update(before, "{}", file("image", "c.png", "image/png", PNG2), null, null))
                .andExpect(status().isOk());

        Music after = reload(before);
        assertThat(after.getCover().getObjectKey()).isNotEqualTo(before.getCover().getObjectKey());
        assertThat(storage.keys()).contains(before.getCover().getObjectKey(), after.getCover().getObjectKey());
    }

    @Test
    @DisplayName("판매자가 아닌 사용자의 파일 교체는 400 이고 아무것도 올리지 않는다")
    void nonOwnerCannotReplaceFiles() throws Exception {
        Music before = createSong();
        var keysBefore = storage.keys();

        mockMvc.perform(updateAs(bidder, before, "{}", file("image", "c.png", "image/png", PNG2), null, null))
                .andExpect(status().isBadRequest());

        assertThat(storage.keys()).isEqualTo(keysBefore);
    }

    // --- 삭제: DB 삭제 성공 후 객체 정리 ---

    @Test
    @DisplayName("입찰 없는 곡 삭제: DB 에서 지운 뒤 세 객체를 모두 지운다")
    void deleteRemovesAllObjectsAfterDb() throws Exception {
        Music music = createSong();

        mockMvc.perform(delete("/api/music/{id}", music.getMusicUuid()).header("Authorization", bearer(composer)))
                .andExpect(status().isOk());

        assertThat(musicRepository.findById(music.getMusicUuid())).isEmpty();
        assertThat(storage.keys()).isEmpty();
    }

    @Test
    @DisplayName("입찰 있는 곡 삭제는 409 이고 객체도 그대로 남는다")
    void deleteWithBidKeepsObjects() throws Exception {
        Music music = createSong();
        saveBid(music, bidder);

        mockMvc.perform(delete("/api/music/{id}", music.getMusicUuid()).header("Authorization", bearer(composer)))
                .andExpect(status().isConflict());

        assertThat(storage.keys()).hasSize(3);
    }

    @Test
    @DisplayName("DB 삭제 후 객체 정리 실패: 삭제는 성공(200)하고 지우지 못한 객체만 고아로 남는다")
    void deleteCleanupFailureLeavesOrphanOnly() throws Exception {
        Music music = createSong();
        storage.failDeleteOf("/full-demo/");

        mockMvc.perform(delete("/api/music/{id}", music.getMusicUuid()).header("Authorization", bearer(composer)))
                .andExpect(status().isOk());

        assertThat(musicRepository.findById(music.getMusicUuid())).isEmpty();
        assertThat(storage.keys()).containsExactly(music.getFullDemo().getObjectKey());
    }

    // --- 헬퍼 ---

    /** 등록 API 로 만든 곡(세 자산 모두 객체 저장소). */
    private Music createSong() throws Exception {
        mockMvc.perform(validCreate("valid title")).andExpect(status().isCreated());
        return musicRepository.findAll().get(0);
    }

    /** 백필을 마친 기존 곡 — 커버·전체 데모는 legacy 키, 미리듣기는 없다. */
    private Music saveLegacySong() {
        Music music = new Music();
        music.setTitle("legacy");
        music.setUser(composer);
        music.setStartingPrice(10_000L);
        music.setStatus(0);
        music.setAuctionEndTime(LocalDateTime.now().plusDays(3));
        UUID musicUuid = UUID.randomUUID();
        music.setMusicUuid(musicUuid);
        music.setCover(new MediaObject("music/" + musicUuid + "/cover/legacy", "application/octet-stream", 1L, null));
        music.setFullDemo(new MediaObject("music/" + musicUuid + "/full-demo/legacy", "application/octet-stream", 1L, null));
        music.setAuctionFailureEmailSent(false);
        music.setShowAllBids(false);
        music.setPopularComposer(false);
        music.setSteadyWorkComposer(false);
        music.setHitSongComposer(false);
        return musicRepository.save(music);
    }

    private void saveBid(Music music, User user) {
        Bid bid = new Bid();
        bid.setMusic(music);
        bid.setUser(user);
        bid.setPrice(11_000L);
        bid.setBidderEmailSent(false);
        bid.setComposerEmailSent(false);
        bidRepository.save(bid);
    }

    private Music reload(Music music) {
        return musicRepository.findById(music.getMusicUuid()).orElseThrow();
    }

    private String bearer(User user) {
        return "Bearer " + jwtUtil.createJwt(user.getEmail(), user.getRole());
    }

    private MockMultipartHttpServletRequestBuilder update(Music music, String json, MockMultipartFile cover,
                                                          MockMultipartFile preview, MockMultipartFile fullDemo) {
        return updateAs(composer, music, json, cover, preview, fullDemo);
    }

    private MockMultipartHttpServletRequestBuilder updateAs(User user, Music music, String json, MockMultipartFile cover,
                                                            MockMultipartFile preview, MockMultipartFile fullDemo) {
        MockMultipartHttpServletRequestBuilder request = multipart(HttpMethod.PUT, "/api/music/{id}", music.getMusicUuid());
        request.file(new MockMultipartFile("music", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes(StandardCharsets.UTF_8)));
        for (MockMultipartFile part : new MockMultipartFile[]{cover, preview, fullDemo}) {
            if (part != null) {
                request.file(part);
            }
        }
        request.header("Authorization", bearer(user));
        return request;
    }

    private MockMultipartHttpServletRequestBuilder validCreate(String title) {
        return create(title, file("image", "c.png", "image/png", PNG),
                file("preview", "p.mp3", "audio/mpeg", MP3),
                file("audio", "f.wav", "audio/wav", WAV));
    }

    private MockMultipartHttpServletRequestBuilder create(String title, MockMultipartFile cover,
                                                          MockMultipartFile preview, MockMultipartFile fullDemo) {
        String json = "{\"title\":\"" + title + "\",\"startingPrice\":10000,\"majorGenre\":\"POP\","
                + "\"musicPeriod\":3,\"showAllBids\":true}";
        MockMultipartHttpServletRequestBuilder request = multipart("/api/music/create");
        request.file(new MockMultipartFile("music", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes(StandardCharsets.UTF_8)));
        for (MockMultipartFile part : new MockMultipartFile[]{cover, preview, fullDemo}) {
            if (part != null) {
                request.file(part);
            }
        }
        request.header("Authorization", "Bearer " + jwtUtil.createJwt(composer.getEmail(), composer.getRole()));
        return request;
    }

    private static MockMultipartFile file(String part, String name, String declaredType, byte[] content) {
        return new MockMultipartFile(part, name, declaredType, content);
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
