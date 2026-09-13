package com.notenest.payment;

import com.notenest.batch.FixedClockTestConfig;
import com.notenest.config.QueryDslConfig;
import com.notenest.domain.Bid;
import com.notenest.domain.Music;
import com.notenest.domain.Payment;
import com.notenest.domain.User;
import com.notenest.dto.CreateBidDTO;
import com.notenest.dto.PaymentReq;
import com.notenest.repository.BidRepository;
import com.notenest.repository.MusicRepository;
import com.notenest.repository.PaymentRepository;
import com.notenest.repository.UserRepository;
import com.notenest.service.BidServiceImpl;
import com.notenest.service.DownloadService;
import com.notenest.service.EmailService;
import com.notenest.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 입찰→낙찰→PG 대역 금액 검증→결제 저장→정산의 백엔드 통합 검증(H2, PG 대역).
 *
 * <p>실행 격리·논리 시각 제어는 배치 특성화 하네스와 동일한 방식이다:
 * <ul>
 *   <li>@DataJpaTest 라 앱의 @EnableScheduling 이 로드되지 않아 스케줄러 레이스가 없다.</li>
 *   <li>REQUIRES_NEW 서비스(processAuctionEnd/processPayment/processPaymentFollowUp)가
 *       픽스처를 볼 수 있도록 테스트 메서드는 비트랜잭션(NOT_SUPPORTED)으로 돌리고 수동 정리한다.</li>
 *   <li>고정 Clock 으로 마감/기한을 결정적으로 만든다.</li>
 * </ul>
 * PG는 {@link FakePaymentGateway} 대역으로 금액을 주입한다(실제 결제·취소 호출 없음).
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueryDslConfig.class, BidServiceImpl.class, PaymentService.class,
        FixedClockTestConfig.class, PaymentAmountFlowIntegrationTest.GatewayConfig.class})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment-flow;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
class PaymentAmountFlowIntegrationTest {

    @TestConfiguration
    static class GatewayConfig {
        @Bean
        FakePaymentGateway fakePaymentGateway() {
            return new FakePaymentGateway();
        }
    }

    @Autowired private BidServiceImpl bidService;
    @Autowired private PaymentService paymentService;
    @Autowired private FakePaymentGateway gateway;

    @Autowired private MusicRepository musicRepository;
    @Autowired private BidRepository bidRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private Clock clock;

    @MockBean private EmailService emailService;
    @MockBean private DownloadService downloadService;

    private User composer;
    private User bidderA;

    @BeforeEach
    void reset() {
        // deleteAllInBatch(즉시 DELETE)로 정리한다. deleteAll()은 삭제를 영속성 컨텍스트에 큐잉해
        // @Transactional 테스트에서 insert 가 delete 보다 먼저 flush 되어(Hibernate flush 순서)
        // 이전 비트랜잭션 테스트가 커밋한 user 와 unique 충돌한다. FK 순서로 즉시 삭제한다.
        paymentRepository.deleteAllInBatch();
        bidRepository.deleteAllInBatch();
        musicRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        composer = saveUser("composer@test.local", "composer");
        bidderA = saveUser("bidderA@test.local", "bidderA");
        gateway.cancellations.clear();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private User saveUser(String email, String nickname) {
        User u = new User();
        u.setEmail(email);
        u.setNickname(nickname);
        u.setName(nickname);
        u.setPassword("x");
        u.setRole("USER");
        u.setEmailVerified(true);
        u.setAgreement(true);
        return userRepository.save(u);
    }

    private Music saveEndedMusicWithStartingPrice(long startingPrice) {
        Music m = new Music();
        m.setTitle("amount-track");
        m.setUser(composer);
        m.setStatus(0);
        m.setStartingPrice(startingPrice);
        m.setAuctionEndTime(now().minusDays(1)); // 마감 지난 곡(낙찰 대상)
        m.setAuctionFailureEmailSent(false);
        m.setShowAllBids(false);
        m.setPopularComposer(false);
        m.setSteadyWorkComposer(false);
        m.setHitSongComposer(false);
        return musicRepository.save(m);
    }

    private Bid saveBid(Music music, User user, long price) {
        Bid bid = new Bid();
        bid.setMusic(music);
        bid.setUser(user);
        bid.setPrice(price);
        bid.setStatus(null);
        bid.setBidderEmailSent(false);
        bid.setComposerEmailSent(false);
        return bidRepository.save(bid);
    }

    @Test
    @Transactional // 세션 유지 — createBid 의 LAZY 네비게이션과 검증만 확인하고 롤백한다.
    @DisplayName("createBid: 원 단위 정수 입찰가가 그대로 저장되고, 시작가 미만은 거부된다")
    void createBid_storesWonPrice_andRejectsBelowStartingPrice() {
        Music music = saveEndedMusicWithStartingPrice(10000L);

        CreateBidDTO dto = new CreateBidDTO();
        dto.setMusicUuid(music.getMusicUuid());
        dto.setPrice(11000L);

        Bid saved = bidService.createBid(dto, bidderA.getEmail());
        assertThat(saved.getPrice()).isEqualTo(11000L);

        CreateBidDTO tooLow = new CreateBidDTO();
        tooLow.setMusicUuid(music.getMusicUuid());
        tooLow.setPrice(9000L);
        assertThatThrownBy(() -> bidService.createBid(tooLow, bidderA.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시작 가격");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("정상 흐름: 낙찰→PG 금액 일치→결제 원 단위 저장→정산 COMPLETED")
    void happyPath_savesPaymentInWon_andSettles() throws Exception {
        Music music = saveEndedMusicWithStartingPrice(10000L);
        Bid bid = saveBid(music, bidderA, 11000L);

        // 낙찰 처리 → 최고가 입찰이 PENDING
        bidService.processAuctionEnd(music.getMusicUuid());
        assertThat(bidRepository.findById(bid.getBidUuid()).orElseThrow().getStatus()).isEqualTo("PENDING");

        // PG 대역이 입찰가와 같은 금액(원 단위)을 반환
        gateway.paidAmountWon = 11000L;
        PaymentReq req = new PaymentReq();
        req.setImpUid("imp_flow_ok");
        req.setBidUuid(bid.getBidUuid());
        paymentService.processPayment(req, bidderA.getEmail());

        // 결제가 원 단위 그대로 저장됨
        Bid reloaded = bidRepository.findById(bid.getBidUuid()).orElseThrow();
        Payment payment = paymentRepository.findByBid(reloaded);
        assertThat(payment).isNotNull();
        assertThat(payment.getStatus()).isEqualTo("PAID");
        assertThat(payment.getPrice()).isEqualTo(11000L);
        assertThat(reloaded.getImpUid()).isEqualTo("imp_flow_ok");
        assertThat(gateway.cancellations).isEmpty();

        // 정산 → COMPLETED
        bidService.processPaymentFollowUp(music.getMusicUuid());
        assertThat(bidRepository.findById(bid.getBidUuid()).orElseThrow().getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("금액 불일치: 결제 취소 호출 + 결제 레코드 미저장 + 입찰 impUid 미기록")
    void mismatch_cancelsAndPersistsNothing() throws Exception {
        Music music = saveEndedMusicWithStartingPrice(10000L);
        Bid bid = saveBid(music, bidderA, 11000L);
        bidService.processAuctionEnd(music.getMusicUuid());

        gateway.paidAmountWon = 1_100_000L; // 과거 *100 배율 금액 → 불일치
        PaymentReq req = new PaymentReq();
        req.setImpUid("imp_flow_bad");
        req.setBidUuid(bid.getBidUuid());

        assertThatThrownBy(() -> paymentService.processPayment(req, bidderA.getEmail()))
                .isInstanceOf(IllegalArgumentException.class);

        Bid reloaded = bidRepository.findById(bid.getBidUuid()).orElseThrow();
        assertThat(paymentRepository.findByBid(reloaded)).isNull();
        assertThat(reloaded.getImpUid()).isNull();
        assertThat(gateway.cancellations).anyMatch(c -> c.startsWith("imp_flow_bad:"));
    }
}
