package com.notenest.contract;

import com.notenest.domain.Bid;
import com.notenest.domain.Music;
import com.notenest.domain.MusicalKey;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [NB5] 곡 등록·수정의 BPM·musicalKey 입력 계약.
 *
 *  - BPM: 40~250 정수(선택). 범위 밖·소수는 400 이고 파일을 올리지 않는다.
 *  - 키: 명확한 영문 표기를 enum 으로 정규화해 저장한다. 모호한 표기(AM)·한글 음이름은 400.
 *  - 첫 입찰 후에도 BPM·키는 수정할 수 있다(전체 데모 교체 금지와 다르다).
 *  - 값이 없는 기존 곡은 null 이고, 상세 JSON 에서 필드가 빠진다(non_null).
 *
 * 인프라: MusicStorageFlowTest 와 같은 H2(MySQL 모드) + MockMvc + fake 저장소.
 */
@SpringBootTest(properties = {
        "spring.jwt.secret=test-secret-key-for-notenest-builds",
        "spring.datasource.url=jdbc:h2:mem:music-attributes;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MusicAttributesFlowTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    private static final byte[] MP3 = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, 9, 9};

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
    @Autowired private UserRepository userRepository;
    @Autowired private MusicRepository musicRepository;
    @Autowired private BidRepository bidRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private LikeRepository likeRepository;

    @MockBean private BidServiceImpl bidService;
    @MockBean private EmailService emailService;

    private User composer;
    private User bidder;

    @BeforeEach
    void reset() {
        when(bidService.getAllBidsByMusic(any(), any())).thenReturn(Page.empty());
        storage.reset();
        likeRepository.deleteAll();
        paymentRepository.deleteAll();
        bidRepository.deleteAll();
        musicRepository.deleteAll();
        userRepository.deleteAll();
        composer = saveUser("composer@test.local", "composer");
        bidder = saveUser("bidder@test.local", "bidder");
    }

    @Test
    @DisplayName("등록: 영문 키 표기를 enum 으로 정규화해 저장하고 상세 응답에 bpm·musicalKey 를 싣는다")
    void createNormalizesAndStores() throws Exception {
        mockMvc.perform(create(",\"bpm\":92,\"musicalKey\":\"a min\"")).andExpect(status().isCreated());

        Music music = onlyMusic();
        assertThat(music.getBpm()).isEqualTo(92);
        assertThat(music.getMusicalKey()).isEqualTo(MusicalKey.A_MINOR);
        mockMvc.perform(get("/api/music/{id}", music.getMusicUuid()).header("Authorization", bearer(bidder)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bpm").value(92))
                .andExpect(jsonPath("$.musicalKey").value("A_MINOR"));
    }

    @Test
    @DisplayName("등록: BPM·키를 보내지 않으면 null 로 저장되고 상세 JSON 에서 두 필드가 빠진다(기존 곡과 같은 모양)")
    void createWithoutAttributesKeepsNull() throws Exception {
        mockMvc.perform(create("")).andExpect(status().isCreated());

        Music music = onlyMusic();
        assertThat(music.getBpm()).isNull();
        assertThat(music.getMusicalKey()).isNull();
        mockMvc.perform(get("/api/music/{id}", music.getMusicUuid()).header("Authorization", bearer(bidder)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bpm").doesNotExist())
                .andExpect(jsonPath("$.musicalKey").doesNotExist());
    }

    @Test
    @DisplayName("등록: BPM 범위 밖(39·251)·소수(120.5), 모호한 키(AM)·한글 음이름은 400 이고 파일을 하나도 올리지 않는다")
    void createRejectsInvalidAttributesBeforeUpload() throws Exception {
        for (String extra : new String[]{",\"bpm\":39", ",\"bpm\":251", ",\"bpm\":120.5",
                ",\"musicalKey\":\"AM\"", ",\"musicalKey\":\"라단조\""}) {
            mockMvc.perform(create(extra)).andExpect(status().isBadRequest());
        }
        assertThat(storage.keys()).isEmpty();
        assertThat(musicRepository.count()).isZero();
    }

    @Test
    @DisplayName("수정: 첫 입찰 이후에도 BPM·키를 바꿀 수 있다")
    void updateAllowedAfterFirstBid() throws Exception {
        mockMvc.perform(create(",\"bpm\":92,\"musicalKey\":\"Am\"")).andExpect(status().isCreated());
        Music music = onlyMusic();
        saveBid(music);

        mockMvc.perform(update(music, "{\"bpm\":128,\"musicalKey\":\"F# minor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bpm").value(128))
                .andExpect(jsonPath("$.musicalKey").value("F_SHARP_MINOR"));

        Music after = onlyMusic();
        assertThat(after.getBpm()).isEqualTo(128);
        assertThat(after.getMusicalKey()).isEqualTo(MusicalKey.F_SHARP_MINOR);
    }

    @Test
    @DisplayName("수정: 보내지 않은 속성은 그대로 두고, 잘못된 값은 400 이며 DB 를 바꾸지 않는다")
    void updateKeepsOmittedAndRejectsInvalid() throws Exception {
        mockMvc.perform(create(",\"bpm\":92,\"musicalKey\":\"Am\"")).andExpect(status().isCreated());
        Music music = onlyMusic();

        mockMvc.perform(update(music, "{\"title\":\"renamed\"}")).andExpect(status().isOk());
        assertThat(onlyMusic().getBpm()).isEqualTo(92);
        assertThat(onlyMusic().getMusicalKey()).isEqualTo(MusicalKey.A_MINOR);

        mockMvc.perform(update(music, "{\"bpm\":300}")).andExpect(status().isBadRequest());
        mockMvc.perform(update(music, "{\"musicalKey\":\"AM\",\"title\":\"should-not-apply\"}")).andExpect(status().isBadRequest());
        Music after = onlyMusic();
        assertThat(after.getBpm()).isEqualTo(92);
        assertThat(after.getMusicalKey()).isEqualTo(MusicalKey.A_MINOR);
        assertThat(after.getTitle()).isEqualTo("renamed");
    }

    // --- 헬퍼 ---

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

    private Music onlyMusic() {
        return musicRepository.findAll().get(0);
    }

    private void saveBid(Music music) {
        Bid bid = new Bid();
        bid.setMusic(music);
        bid.setUser(bidder);
        bid.setPrice(11_000L);
        bid.setBidderEmailSent(false);
        bid.setComposerEmailSent(false);
        bidRepository.save(bid);
    }

    private String bearer(User user) {
        return "Bearer " + jwtUtil.createJwt(user.getEmail(), user.getRole());
    }

    /** extraJson 은 기본 필드 뒤에 붙는 JSON 조각(",\"bpm\":92" 등). */
    private MockMultipartHttpServletRequestBuilder create(String extraJson) {
        String json = "{\"title\":\"t\",\"startingPrice\":10000,\"majorGenre\":\"pop\",\"musicPeriod\":3,\"showAllBids\":true"
                + extraJson + "}";
        MockMultipartHttpServletRequestBuilder request = multipart("/api/music/create");
        request.file(new MockMultipartFile("music", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes(StandardCharsets.UTF_8)));
        request.file(new MockMultipartFile("image", "c.png", "image/png", PNG));
        request.file(new MockMultipartFile("preview", "p.mp3", "audio/mpeg", MP3));
        request.file(new MockMultipartFile("audio", "f.mp3", "audio/mpeg", MP3));
        request.header("Authorization", bearer(composer));
        return request;
    }

    private MockMultipartHttpServletRequestBuilder update(Music music, String json) {
        MockMultipartHttpServletRequestBuilder request = multipart(HttpMethod.PUT, "/api/music/{id}", music.getMusicUuid());
        request.file(new MockMultipartFile("music", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes(StandardCharsets.UTF_8)));
        request.header("Authorization", bearer(composer));
        return request;
    }
}
