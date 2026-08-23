package com.notenest.batch;

import com.notenest.config.QueryDslConfig;
import com.notenest.domain.Bid;
import com.notenest.domain.Music;
import com.notenest.domain.Payment;
import com.notenest.domain.User;
import com.notenest.repository.BidRepository;
import com.notenest.repository.MusicRepository;
import com.notenest.repository.PaymentRepository;
import com.notenest.repository.UserRepository;
import com.notenest.service.BidServiceImpl;
import com.notenest.service.DownloadService;
import com.notenest.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

/**
 * 경매 마감 배치(processAuctionEnd)의 "현행(변경 전) 동작"을 고정하는 특성화 테스트 공용 하네스.
 *
 * 이 클래스 자체는 검증하지 않는다. 실행 격리·픽스처 헬퍼만 제공하고, 실제 시나리오는
 * 하위 클래스가 @Test 로 기술한다. (Spring 테스트 애노테이션은 상위 클래스에서 상속된다.)
 *
 * 실행 격리 설계:
 *  - @DataJpaTest 라 메인 앱의 @EnableScheduling 이 로드되지 않는다 → 10초 스케줄러가
 *    픽스처를 가로채는 레이스가 원천 차단된다.
 *  - H2 를 MySQL 모드로 띄우고 NON_KEYWORDS=USER 로 예약어를 풀어, Music 의
 *    LONGBLOB/TEXT 컬럼과 @Table(name="user") DDL 을 그대로 수용한다.
 *  - processAuctionEnd 가 @Transactional(REQUIRES_NEW) 이므로, 하위 테스트 메서드는
 *    @Transactional(NOT_SUPPORTED)(비트랜잭션)로 돌려 픽스처 저장을 즉시 커밋시킨다.
 *    그래야 내부 새 트랜잭션의 findById 가 픽스처를 READ_COMMITTED 로 볼 수 있다.
 *
 * 논리 시각 주입 불가 대응:
 *  - 현행 코드는 내부에서 LocalDateTime.now() 를 쓴다(Clock 주입 없음). 따라서 분기는
 *    auctionEndTime 을 now 기준 상대값으로 두어 재현한다.
 *      · 경매 종료:        auctionEndTime < now
 *      · 1순위 결제기한 만료: auctionEndTime < now-3d  (deadline1 = end+3d)
 *      · 2순위 결제기한 만료: auctionEndTime < now-6d  (deadline2 = end+6d)
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
abstract class AuctionEndCharacterizationSupport {

    @Autowired
    protected BidServiceImpl bidService;

    @Autowired
    protected MusicRepository musicRepository;

    @Autowired
    protected BidRepository bidRepository;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PaymentRepository paymentRepository;

    // 실제 메일 인프라(JavaMailSender 등)를 띄우지 않기 위해 목으로 대체하고 호출 횟수만 검증한다.
    @MockBean
    protected EmailService emailService;

    // BidServiceImpl 이 주입받지만 경매 마감 경로에서는 쓰이지 않는다. 컨텍스트 구성을 위해 목으로 채운다.
    @MockBean
    protected DownloadService downloadService;

    protected User composer;
    protected User bidderA;
    protected User bidderB;

    @BeforeEach
    void resetFixtures() {
        // NOT_SUPPORTED 라 롤백이 없으므로 FK 순서(payment→bid→music→user)로 직접 비운다.
        paymentRepository.deleteAll();
        bidRepository.deleteAll();
        musicRepository.deleteAll();
        userRepository.deleteAll();

        composer = saveUser("composer@test.local", "composer");
        bidderA = saveUser("bidderA@test.local", "bidderA");
        bidderB = saveUser("bidderB@test.local", "bidderB");
    }

    // --- 픽스처 헬퍼 ---

    protected User saveUser(String email, String nickname) {
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

    /** 마감 시각만 지정한 종료 대상 곡. status=0, 각 nullable=false Boolean 은 저장을 위해 채운다. */
    protected Music saveEndedMusic(LocalDateTime auctionEndTime) {
        Music music = new Music();
        music.setTitle("characterization-track");
        music.setUser(composer);
        music.setStatus(0);
        music.setAuctionEndTime(auctionEndTime);
        music.setAuctionFailureEmailSent(false);
        music.setShowAllBids(false);
        music.setPopularComposer(false);
        music.setSteadyWorkComposer(false);
        music.setHitSongComposer(false);
        return musicRepository.save(music);
    }

    protected Bid saveBid(Music music, User user, double price) {
        Bid bid = new Bid();
        bid.setMusic(music);
        bid.setUser(user);
        bid.setPrice(price);
        bid.setStatus(null); // 진행 중(미처리)
        bid.setBidderEmailSent(false);
        bid.setComposerEmailSent(false);
        return bidRepository.save(bid);
    }

    protected Payment savePayment(Bid bid, String status) {
        Payment payment = new Payment();
        payment.setBid(bid);
        payment.setImpUid("imp_" + status);
        payment.setPrice(bid.getPrice());
        payment.setStatus(status);
        return paymentRepository.save(payment);
    }

    /** 입찰 상태를 DB 에서 다시 읽어 반환(직접 컬럼이라 트랜잭션 밖에서도 안전). */
    protected String status(Bid bid) {
        return bidRepository.findById(bid.getBidUuid()).orElseThrow().getStatus();
    }
}
