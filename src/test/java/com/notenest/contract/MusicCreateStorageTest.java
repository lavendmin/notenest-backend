package com.notenest.contract;

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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 곡 등록의 객체 저장소 전환과 부분 실패 보상 계약.
 *
 * 객체 저장소와 DB 는 한 트랜잭션으로 묶이지 않는다. 실제 S3 는 원하는 순간에 실패시킬 수 없으므로
 * 실패를 주입할 수 있는 {@link FakeObjectStorage} 로 저장소를 바꿔 다음을 검증한다.
 *  - 정상: 세 파일이 music/{musicUuid}/{자산}/{assetUuid} 키로 올라가고 DB 에는 키·메타데이터만 남는다(LOB 없음)
 *  - 검증 실패: 아무것도 올리지 않는다
 *  - 업로드 중 실패: 이미 올린 파일을 지우고 곡을 저장하지 않는다
 *  - DB 저장 실패: 올린 파일 세 개를 모두 지운다
 *  - 보상 삭제마저 실패: 원래 실패를 그대로 응답하고, 남은 객체는 고아로 기록된다
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
class MusicCreateStorageTest {

    private static final byte[] PNG = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3);
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

    @BeforeEach
    void reset() {
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

        assertThat(music.getImage()).as("신규 곡은 LOB 에 바이트를 쓰지 않는다").isNull();
        assertThat(music.getAudio()).isNull();
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

    // --- 헬퍼 ---

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
