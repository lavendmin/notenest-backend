package com.notenest.batch;

import com.notenest.domain.Bid;
import com.notenest.domain.Music;
import com.notenest.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [경매 라이프사이클 시나리오 — 마감 잡 + 결제 후속 잡의 분리된 파이프라인]
 *
 * Step 4a 에서 하나였던 processAuctionEnd 를 두 작업으로 분리했다.
 *   - 마감 잡:      processAuctionEnd  (status 0→1, 최초 낙찰자 선정, 나머지 FAILED)
 *   - 결제 후속 잡: processPaymentFollowUp (PENDING 대상의 정산·기한 만료·차순위 승계, 한 사이클 1전이)
 *
 * 각 시나리오는 실제 스케줄러처럼 "마감 → 결제 후속(필요 횟수만큼)"으로 호출해 최종 상태·이메일
 * 호출을 고정한다. 관찰 가능한 최종 상태는 분리 이전(Step 1~3 특성화)과 동일하다.
 * 실행 격리·픽스처 헬퍼는 {@link AuctionEndCharacterizationSupport} 참고.
 */
class AuctionLifecycleScenariosTest extends AuctionEndCharacterizationSupport {

    @Test
    @DisplayName("① 무입찰 종료: 마감 잡에서 종료 처리 후 작곡가 실패 메일 1회, 결제 후속 잡은 무처리")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void noBids_endsAsFailure_composerNotifiedOnce() throws Exception {
        Music music = saveEndedMusic(LocalDateTime.now().minusDays(1));

        bidService.processAuctionEnd(music.getMusicUuid());
        bidService.processPaymentFollowUp(music.getMusicUuid()); // PENDING 없음 → no-op

        Music after = musicRepository.findById(music.getMusicUuid()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(1);
        assertThat(after.isAuctionFailureEmailSent()).isTrue();
        verify(emailService, times(1)).sendAuctionFailureToComposer(any(User.class), any(Music.class));
        verify(emailService, never()).sendBidSuccessToBidder(any(User.class), any(Music.class));
        verify(emailService, never()).sendBidSuccessToComposer(any(User.class), any(Music.class));
    }

    @Test
    @DisplayName("② 1순위 결제 완료(PAID): 결제 후속 잡에서 COMPLETED 전이, 작곡가 성공 메일 1회")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void firstBidderPaid_transitionsToCompleted_composerSuccessMailOnce() throws Exception {
        // 결제기한(D+3) 전. A@3000(1순위) 결제 완료, B@2000(다른 사용자)
        Music music = saveEndedMusic(LocalDateTime.now().minusDays(1));
        Bid bidA1 = saveBid(music, bidderA, 3000);
        Bid bidB1 = saveBid(music, bidderB, 2000);
        savePayment(bidA1, "PAID");

        bidService.processAuctionEnd(music.getMusicUuid());        // A PENDING, B FAILED
        bidService.processPaymentFollowUp(music.getMusicUuid());   // A PAID → COMPLETED

        assertThat(status(bidA1)).isEqualTo("COMPLETED");
        assertThat(status(bidB1)).isEqualTo("FAILED");
        verify(emailService, times(1)).sendBidSuccessToBidder(any(User.class), any(Music.class));
        verify(emailService, times(1)).sendBidSuccessToComposer(any(User.class), any(Music.class));
        verify(emailService, never()).sendAuctionFailureToComposer(any(User.class), any(Music.class));

        Music after = musicRepository.findById(music.getMusicUuid()).orElseThrow();
        assertThat(after.isAuctionFailureEmailSent()).isFalse();
    }

    @Test
    @DisplayName("③ 1순위 기한 만료 + 2순위가 다른 사용자: 결제 후속 잡에서 2순위를 PENDING 으로 승계")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void firstDeadlineExpired_differentSecondBidder_promotedToPending() throws Exception {
        // 1순위 기한(D+3)은 지났으나 2순위 기한(D+6)은 안 지남. A@3000(1순위), B@2000(2순위, 다른 사용자)
        Music music = saveEndedMusic(LocalDateTime.now().minusDays(4));
        Bid bidA1 = saveBid(music, bidderA, 3000);
        Bid bidB1 = saveBid(music, bidderB, 2000);

        bidService.processAuctionEnd(music.getMusicUuid());        // A PENDING, B FAILED
        bidService.processPaymentFollowUp(music.getMusicUuid());   // A 기한만료 → FAILED, B 승계 PENDING

        assertThat(status(bidA1)).isEqualTo("FAILED");
        assertThat(status(bidB1)).isEqualTo("PENDING");
        verify(emailService, times(2)).sendBidSuccessToBidder(any(User.class), any(Music.class));
        verify(emailService, never()).sendAuctionFailureToComposer(any(User.class), any(Music.class));
        verify(emailService, never()).sendBidSuccessToComposer(any(User.class), any(Music.class));

        Music after = musicRepository.findById(music.getMusicUuid()).orElseThrow();
        assertThat(after.isAuctionFailureEmailSent()).isFalse();
    }

    @Test
    @DisplayName("④ 최종 후보까지 실패: 결제 후속 잡 2사이클(1순위 실패→2순위 승계, 2순위 실패→무산)")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void bothDeadlinesExpired_endsAsFailure_composerNotifiedOnce() throws Exception {
        // 1순위(D+3)·2순위(D+6) 기한 모두 경과. A@3000(1순위), B@2000(2순위, 다른 사용자), 결제 없음
        Music music = saveEndedMusic(LocalDateTime.now().minusDays(7));
        Bid bidA1 = saveBid(music, bidderA, 3000);
        Bid bidB1 = saveBid(music, bidderB, 2000);

        bidService.processAuctionEnd(music.getMusicUuid());        // A PENDING, B FAILED
        bidService.processPaymentFollowUp(music.getMusicUuid());   // 사이클1: A FAILED, B 승계 PENDING
        bidService.processPaymentFollowUp(music.getMusicUuid());   // 사이클2: B 기한만료 → FAILED, 경매 무산

        assertThat(status(bidA1)).isEqualTo("FAILED");
        assertThat(status(bidB1)).isEqualTo("FAILED");
        verify(emailService, times(2)).sendBidSuccessToBidder(any(User.class), any(Music.class));
        verify(emailService, times(1)).sendAuctionFailureToComposer(any(User.class), any(Music.class));
        verify(emailService, never()).sendBidSuccessToComposer(any(User.class), any(Music.class));

        Music after = musicRepository.findById(music.getMusicUuid()).orElseThrow();
        assertThat(after.isAuctionFailureEmailSent()).isTrue();
    }
}
