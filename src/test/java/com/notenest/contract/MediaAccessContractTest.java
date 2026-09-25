package com.notenest.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notenest.domain.Bid;
import com.notenest.domain.Likes;
import com.notenest.domain.Music;
import com.notenest.domain.User;
import com.notenest.dto.UserDTO;
import com.notenest.jwt.JWTUtil;
import com.notenest.repository.BidRepository;
import com.notenest.repository.LikeRepository;
import com.notenest.repository.MusicRepository;
import com.notenest.repository.PaymentRepository;
import com.notenest.repository.UserRepository;
import com.notenest.service.BidServiceImpl;
import com.notenest.service.EmailService;
import com.notenest.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NB1 미디어 접근 계약 테스트.
 *
 * 접근 정책(2026-09-25 확정):
 *  - 곡 목록은 비로그인에게도 공개한다(좋아요 여부는 false).
 *  - 곡 상세부터는 로그인이 필요하고, 상세·목록류 응답에는 전체 음원을 싣지 않는다.
 *  - 전체 데모는 판매자 본인 또는 결제 완료(COMPLETED) 낙찰자만 받는다. 다운로드 횟수는 제한하지 않는다.
 *  - 첫 입찰 발생 후에는 곡을 삭제할 수 없다.
 *
 * 이 클래스는 처음에 결함을 현재 동작 그대로 고정한 뒤, 권한 수정 커밋에서 목표 계약으로 뒤집었다.
 *  - {@link AccessContracts}: 뒤집힌 계약. 각 DisplayName 의 D 번호는 결함 인벤토리 번호다.
 *  - {@link KeptContracts}: 전환 전부터 성립했고 이후에도 유지해야 하는 계약.
 *
 * 인프라: 시드 의존 없이 항상 실행되도록 H2(MySQL 모드) + MockMvc 를 쓰고, <b>보안 필터를 켠다</b>(addFilters 기본값).
 * 인증은 LoginFilter 대신 {@link JWTUtil#createJwt} 로 발급한 실제 토큰을 Authorization 헤더에 싣는다.
 *
 * 스케줄러: {@link BidServiceImpl} 을 목으로 대체해 @Scheduled 경매 배치가 픽스처를 변형하지 못하게 한다.
 * 상세가 쓰는 입찰 목록 조회만 빈 페이지로 스텁한다.
 */
@SpringBootTest(properties = {
        "spring.jwt.secret=test-secret-key-for-notenest-builds",
        // user 는 H2 예약어라 @Table(name="user") DDL/쿼리가 깨진다 → NON_KEYWORDS 로 제외.
        "spring.datasource.url=jdbc:h2:mem:media-contract;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MediaAccessContractTest {

    // 구분 가능한 픽스처 바이트 — 응답·다운로드 본문이 "어느 파일"인지 바이트로 판별한다.
    private static final byte[] COVER = "cover-image-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FULL_AUDIO = "full-demo-audio-bytes".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JWTUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MusicRepository musicRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private UserService userService;

    @MockBean
    private BidServiceImpl bidService;

    @MockBean
    private EmailService emailService;

    private User seller;
    private User completedWinner;
    private User pendingWinner;
    private User stranger;

    @BeforeEach
    void resetFixtures() {
        when(bidService.getAllBidsByMusic(any(), any())).thenReturn(Page.empty());

        // 트랜잭션 롤백 없이 요청마다 커밋되므로 FK 순서(likes→payment→bid→music→user)로 직접 비운다.
        likeRepository.deleteAll();
        paymentRepository.deleteAll();
        bidRepository.deleteAll();
        musicRepository.deleteAll();
        userRepository.deleteAll();

        seller = saveUser("seller@test.local", "seller");
        completedWinner = saveUser("winner@test.local", "winner");
        pendingWinner = saveUser("pending@test.local", "pending");
        stranger = saveUser("stranger@test.local", "stranger");
    }

    @Nested
    @DisplayName("접근 계약 — NB1 권한 수정으로 뒤집은 결함")
    class AccessContracts {

        @Test
        @DisplayName("D1 곡 목록은 비로그인에게 공개하고 좋아요 여부는 false 다")
        void anonymousCanBrowseList() throws Exception {
            saveMusic();

            JsonNode body = getJson(get("/api/music/filter"), null);

            JsonNode first = body.get("content").get(0);
            assertThat(first.get("title").asText()).isEqualTo("contract-track");
            assertThat(first.get("likedByUser").asBoolean()).isFalse();
            assertThat(first.has("audio")).isFalse();
        }

        @Test
        @DisplayName("D1·D3 비로그인 상세 요청은 401 이다")
        void anonymousDetailIsUnauthorized() throws Exception {
            Music music = saveMusic();

            mockMvc.perform(get("/api/music/{id}", music.getMusicUuid()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("D1 비로그인 곡 등록·다운로드는 401 이다")
        void anonymousWritesAndDownloadsAreUnauthorized() throws Exception {
            Music music = saveMusic();

            mockMvc.perform(multipart("/api/music/create")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/mypage/download/{id}", music.getMusicUuid())).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("D3 로그인 상세 응답에 전체 음원이 없다")
        void detailHasNoFullAudio() throws Exception {
            Music music = saveMusic();

            JsonNode body = getJson(get("/api/music/{id}", music.getMusicUuid()), stranger);

            assertThat(body.has("audio")).isFalse();
        }

        @Test
        @DisplayName("D4 입찰·결제 이력 없는 사용자의 다운로드는 403 이다")
        void strangerCannotDownload() throws Exception {
            Music music = saveMusic();

            mockMvc.perform(get("/api/mypage/download/{id}", music.getMusicUuid()).header("Authorization", bearer(stranger)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("D4 결제 대기(PENDING) 낙찰자의 다운로드는 403 이다")
        void pendingWinnerCannotDownload() throws Exception {
            Music music = saveMusic();
            saveBid(music, pendingWinner, "PENDING");

            mockMvc.perform(get("/api/mypage/download/{id}", music.getMusicUuid()).header("Authorization", bearer(pendingWinner)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("D5 결제 완료 낙찰자는 횟수 제한 없이 다시 받는다 (7회)")
        void completedWinnerDownloadsWithoutCountLimit() throws Exception {
            Music music = saveMusic();
            saveBid(music, completedWinner, "COMPLETED");

            for (int i = 0; i < 7; i++) {
                assertThat(download(music, completedWinner)).isEqualTo(FULL_AUDIO);
            }
        }

        @Test
        @DisplayName("D6 내 곡 목록은 커버만 싣고 음원은 싣지 않는다")
        void myMusicListHasCoverOnly() throws Exception {
            saveMusic();

            JsonNode first = getJson(get("/api/music/my-music"), seller).get("content").get(0);

            assertThat(first.get("image").asText()).isEqualTo(base64(COVER));
            assertThat(first.has("audio")).isFalse();
        }

        @Test
        @DisplayName("D6 찜 목록은 커버만 싣고 음원은 싣지 않는다")
        void likedListHasCoverOnly() throws Exception {
            Music music = saveMusic();
            saveLike(stranger, music);

            JsonNode first = getJson(get("/api/mypage/likes"), stranger).get("content").get(0);

            assertThat(first.get("image").asText()).isEqualTo(base64(COVER));
            assertThat(first.has("audio")).isFalse();
        }

        @Test
        @DisplayName("D7 수정 응답은 상세 DTO 이며 음원·작성자 개인정보가 없다")
        void updateResponseIsDetailDto() throws Exception {
            Music music = saveMusic();
            MockMultipartFile musicPart = new MockMultipartFile(
                    "music", "", MediaType.APPLICATION_JSON_VALUE,
                    "{\"title\":\"renamed\"}".getBytes(StandardCharsets.UTF_8));

            JsonNode body = getJson(multipart(HttpMethod.PUT, "/api/music/{id}", music.getMusicUuid()).file(musicPart), seller);

            assertThat(body.get("title").asText()).isEqualTo("renamed");
            assertThat(body.get("nickName").asText()).isEqualTo("seller");
            assertThat(body.has("audio")).isFalse();
            assertThat(body.has("user")).isFalse();
            assertThat(body.toString()).doesNotContain(seller.getEmail(), seller.getPhoneNo());
        }

        @Test
        @DisplayName("D8 첫 입찰 발생 후 삭제는 409 이고 곡·입찰이 남는다")
        void deleteWithBidsIsRejected() throws Exception {
            Music music = saveMusic();
            saveBid(music, stranger, null);

            mockMvc.perform(delete("/api/music/{id}", music.getMusicUuid()).header("Authorization", bearer(seller)))
                    .andExpect(status().isConflict());

            assertThat(musicRepository.findById(music.getMusicUuid())).isPresent();
            assertThat(bidRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("D8 입찰이 없는 곡은 판매자가 삭제할 수 있다")
        void deleteWithoutBidsSucceeds() throws Exception {
            Music music = saveMusic();

            mockMvc.perform(delete("/api/music/{id}", music.getMusicUuid()).header("Authorization", bearer(seller)))
                    .andExpect(status().isOk());

            assertThat(musicRepository.findById(music.getMusicUuid())).isEmpty();
        }

        @Test
        @DisplayName("D2 가입 기본 역할은 ROLE_USER 다")
        void signUpGrantsUserRole() {
            UserDTO dto = new UserDTO();
            dto.setEmail("newbie@test.local");
            dto.setPassword("password123");
            dto.setPasswordCheck("password123");
            dto.setName("newbie");
            dto.setNickname("newbie");
            dto.setPhoneNo("010-1234-5678");
            dto.setAgreement(true);
            // 이메일 인증 절차(메일 발송·코드 확인)는 이 계약의 관심사가 아니므로 인증 완료 상태만 심는다.
            @SuppressWarnings("unchecked")
            Map<String, Boolean> verified =
                    (Map<String, Boolean>) ReflectionTestUtils.getField(userService, "emailVerificationStatus");
            verified.put(dto.getEmail(), true);

            userService.signUp(dto);

            assertThat(userRepository.findByEmail("newbie@test.local").getRole()).isEqualTo("ROLE_USER");
        }
    }

    @Nested
    @DisplayName("유지 계약 — 전환 전부터 성립했고 이후에도 통과해야 한다")
    class KeptContracts {

        @Test
        @DisplayName("판매자 본인은 전체 음원을 받는다")
        void sellerDownloadsFullAudio() throws Exception {
            Music music = saveMusic();

            assertThat(download(music, seller)).isEqualTo(FULL_AUDIO);
        }

        @Test
        @DisplayName("결제 완료(COMPLETED) 낙찰자는 전체 음원을 받는다")
        void completedWinnerDownloadsFullAudio() throws Exception {
            Music music = saveMusic();
            saveBid(music, completedWinner, "COMPLETED");

            assertThat(download(music, completedWinner)).isEqualTo(FULL_AUDIO);
        }

        @Test
        @DisplayName("상세는 커버 이미지를 준다")
        void detailContainsCover() throws Exception {
            Music music = saveMusic();

            JsonNode body = getJson(get("/api/music/{id}", music.getMusicUuid()), stranger);

            assertThat(body.get("image").asText()).isEqualTo(base64(COVER));
        }

        @Test
        @DisplayName("판매자가 아닌 사용자는 곡을 수정할 수 없다")
        void strangerCannotUpdate() throws Exception {
            Music music = saveMusic();
            MockMultipartFile musicPart = new MockMultipartFile(
                    "music", "", MediaType.APPLICATION_JSON_VALUE,
                    "{\"title\":\"hijacked\"}".getBytes(StandardCharsets.UTF_8));

            mockMvc.perform(multipart(HttpMethod.PUT, "/api/music/{id}", music.getMusicUuid())
                            .file(musicPart)
                            .header("Authorization", bearer(stranger)))
                    .andExpect(status().isBadRequest());

            assertThat(musicRepository.findById(music.getMusicUuid()).orElseThrow().getTitle()).isEqualTo("contract-track");
        }
    }

    // --- 요청 헬퍼 ---

    private String bearer(User user) {
        return "Bearer " + jwtUtil.createJwt(user.getEmail(), user.getRole());
    }

    private JsonNode getJson(RequestBuilder request, User user) throws Exception {
        if (user != null && request instanceof MockHttpServletRequestBuilder builder) {
            builder.header("Authorization", bearer(user));
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private byte[] download(Music music, User user) throws Exception {
        return mockMvc.perform(get("/api/mypage/download/{id}", music.getMusicUuid()).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    // --- 픽스처 헬퍼 ---

    private User saveUser(String email, String nickname) {
        User user = new User();
        user.setEmail(email);
        user.setNickname(nickname);
        user.setName(nickname);
        user.setPassword("x");
        user.setPhoneNo("010-0000-0000");
        user.setRole("ROLE_USER");
        user.setEmailVerified(true);
        user.setAgreement(true);
        return userRepository.save(user);
    }

    /** 진행 중(status=0, 마감 미래) 곡. nullable=false Boolean 은 저장을 위해 채운다. */
    private Music saveMusic() {
        Music music = new Music();
        music.setTitle("contract-track");
        music.setUser(seller);
        music.setStartingPrice(10_000L);
        music.setStatus(0);
        music.setCreatedAt(LocalDateTime.now());
        music.setAuctionEndTime(LocalDateTime.now().plusDays(3));
        music.setImage(COVER);
        music.setAudio(FULL_AUDIO);
        music.setAuctionFailureEmailSent(false);
        music.setShowAllBids(false);
        music.setPopularComposer(false);
        music.setSteadyWorkComposer(false);
        music.setHitSongComposer(false);
        return musicRepository.save(music);
    }

    private Bid saveBid(Music music, User user, String status) {
        Bid bid = new Bid();
        bid.setMusic(music);
        bid.setUser(user);
        bid.setPrice(11_000L);
        bid.setStatus(status);
        bid.setBidderEmailSent(false);
        bid.setComposerEmailSent(false);
        return bidRepository.save(bid);
    }

    private void saveLike(User user, Music music) {
        Likes like = new Likes();
        like.setUser(user);
        like.setMusic(music);
        likeRepository.save(like);
    }
}
