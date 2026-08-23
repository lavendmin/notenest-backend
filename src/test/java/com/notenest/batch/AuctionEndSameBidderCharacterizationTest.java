package com.notenest.batch;

import com.notenest.config.QueryDslConfig;
import com.notenest.domain.Bid;
import com.notenest.domain.Music;
import com.notenest.domain.User;
import com.notenest.repository.BidRepository;
import com.notenest.repository.MusicRepository;
import com.notenest.repository.PaymentRepository;
import com.notenest.repository.UserRepository;
import com.notenest.service.BidServiceImpl;
import com.notenest.service.DownloadService;
import com.notenest.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [특성화 테스트 — 현행(변경 전) 동작 고정]
 *
 * 목적: 하이브리드 정책으로 구현을 바꾸기 "전에", 현재 코드가 실제로
 *       "2등 입찰자가 1등과 같은 사용자면, 그 아래 다른 사용자(3순위)를 보지 않고 경매 무산"
 *       으로 끝난다는 것을 자동 증명한다. (ACTION_PLAN 착수 게이트 1~2단계)
 *
 * 이 테스트가 GREEN 이라는 것은 "지금 코드가 이렇게 동작한다"는 뜻일 뿐,
 * 이 동작이 바람직하다는 뜻이 아니다. Step 3~4에서 하이브리드(사용자별 최고가 dedup →
 * 서로 다른 상위 2명 승계)로 바꾸면 이 테스트의 기대값이 바뀌어야 하며, 그 diff 가
 * "무엇이 어떻게 달라졌는가"의 회귀 증거가 된다.
 *
 * 실행 격리:
 *  - @DataJpaTest 라 @EnableScheduling(메인 앱 클래스)이 로드되지 않는다 → 10초 스케줄러가
 *    픽스처를 가로채는 레이스가 원천 차단된다.
 *  - H2 를 MySQL 모드로 띄워 Music 의 LONGBLOB/TEXT 컬럼 DDL 을 수용한다.
 *  - processAuctionEnd 가 @Transactional(REQUIRES_NEW) 이므로, 테스트 메서드를
 *    NOT_SUPPORTED(비트랜잭션)로 돌려 픽스처 저장을 즉시 커밋시킨다. 그래야 내부 새
 *    트랜잭션의 findById 가 픽스처를 볼 수 있다(READ_COMMITTED).
 *
 * 논리 시각 주입 불가 대응:
 *  - 현행 코드는 내부에서 LocalDateTime.now() 를 쓴다. auctionEndTime 을 now-4일로 두면
 *    "경매 종료(now 이전)"와 "1순위 결제기한 D+3 만료(now-1일)"가 동시에 성립하여
 *    차순위 처리 분기까지 한 번의 호출로 도달한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueryDslConfig.class, BidServiceImpl.class})
@TestPropertySource(properties = {
        // user 는 H2 예약어라 @Table(name="user") DDL/쿼리가 깨진다 → NON_KEYWORDS 로 제외.
        "spring.datasource.url=jdbc:h2:mem:auction-char;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
class AuctionEndSameBidderCharacterizationTest {

    @Autowired
    private BidServiceImpl bidService;

    @Autowired
    private MusicRepository musicRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    // 실제 메일 인프라(JavaMailSender 등)를 띄우지 않기 위해 목으로 대체하고, 호출 횟수만 검증한다.
    @MockBean
    private EmailService emailService;

    // BidServiceImpl 이 주입받지만 경매 마감 경로에서는 쓰이지 않는다. 컨텍스트 구성을 위해 목으로 채운다.
    @MockBean
    private DownloadService downloadService;

    private User composer;
    private User bidderA;
    private User bidderB;

    @BeforeEach
    void cleanDatabase() {
        // NOT_SUPPORTED 로 돌려 롤백이 없으므로, FK 순서(payment→bid→music→user)로 직접 비운다.
        paymentRepository.deleteAll();
        bidRepository.deleteAll();
        musicRepository.deleteAll();
        userRepository.deleteAll();

        composer = saveUser("composer@test.local", "composer");
        bidderA = saveUser("bidderA@test.local", "bidderA");
        bidderB = saveUser("bidderB@test.local", "bidderB");
    }

    @Test
    @DisplayName("현행: 2순위가 1순위와 같은 사용자면, 다른 사용자(3순위)를 승계하지 않고 경매 무산으로 끝난다")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void secondBidderSameAsFirst_currentlyEndsAsFailure_withoutPromotingThirdUniqueUser() throws Exception {
        // given: 마감·결제기한이 모두 지난 곡. 입찰 = A@3000(1순위), A@2500(2순위), B@2000(3순위, 유일한 다른 사용자)
        Music music = saveEndedMusic(LocalDateTime.now().minusDays(4));
        Bid bidA1 = saveBid(music, bidderA, 3000);
        Bid bidA2 = saveBid(music, bidderA, 2500);
        Bid bidB1 = saveBid(music, bidderB, 2000);

        // when: 경매 마감 처리(현행 로직: 첫 낙찰자 선정 + 결제기한 만료 시 차순위 처리까지 한 번에)
        bidService.processAuctionEnd(music.getMusicUuid());

        // then: 곡은 종료 처리(status 0→1)
        Music after = musicRepository.findById(music.getMusicUuid()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(1);

        // then: 세 입찰 모두 FAILED. 특히 B(다른 사용자)는 PENDING 기회를 한 번도 얻지 못한다.
        assertThat(status(bidA1)).isEqualTo("FAILED");
        assertThat(status(bidA2)).isEqualTo("FAILED");
        assertThat(status(bidB1)).isEqualTo("FAILED");

        // then: 경매 무산 처리 — 작곡가에게 실패 메일 1회, 플래그 세팅
        assertThat(after.isAuctionFailureEmailSent()).isTrue();
        verify(emailService, times(1)).sendAuctionFailureToComposer(any(User.class), any(Music.class));

        // then: 낙찰 성공 메일은 오직 1순위(A)에게 최초 마감 시점 1회뿐. B에게는 절대 발송되지 않는다.
        // (넘겨진 User 는 닫힌 트랜잭션의 lazy 프록시라 필드 접근은 못 하지만, 식별자는 초기화 없이 읽힌다.)
        ArgumentCaptor<User> bidderCaptor = ArgumentCaptor.forClass(User.class);
        verify(emailService, times(1)).sendBidSuccessToBidder(bidderCaptor.capture(), any(Music.class));
        assertThat(bidderCaptor.getValue().getUserUUID()).isEqualTo(bidderA.getUserUUID());

        // then: 결제 완료(작곡가 성공)로 이어지는 메일은 없다.
        verify(emailService, never()).sendBidSuccessToComposer(any(User.class), any(Music.class));
    }

    // --- 픽스처 헬퍼 ---

    private User saveUser(String email, String nickname) {
        User user = new User();
        user.setEmail(email);
        user.setNickname(nickname);
        user.setName(nickname);
        user.setPassword("x");
        user.setRole("USER");
        user.setEmailVerified(true);
        user.setAgreement(true);
        return userRepository.save(user);
    }

    private Music saveEndedMusic(LocalDateTime auctionEndTime) {
        Music music = new Music();
        music.setTitle("characterization-track");
        music.setUser(composer);
        music.setStatus(0);
        music.setAuctionEndTime(auctionEndTime);
        music.setAuctionFailureEmailSent(false);
        // nullable=false Boolean 컬럼들 — 경매 경로와 무관하지만 저장을 위해 채운다.
        music.setShowAllBids(false);
        music.setPopularComposer(false);
        music.setSteadyWorkComposer(false);
        music.setHitSongComposer(false);
        return musicRepository.save(music);
    }

    private Bid saveBid(Music music, User user, double price) {
        Bid bid = new Bid();
        bid.setMusic(music);
        bid.setUser(user);
        bid.setPrice(price);
        bid.setStatus(null); // 진행 중(미처리) 상태
        bid.setBidderEmailSent(false);
        bid.setComposerEmailSent(false);
        return bidRepository.save(bid);
    }

    private String status(Bid bid) {
        return bidRepository.findById(bid.getBidUuid()).orElseThrow().getStatus();
    }
}
