package com.notenest.batch;

import com.notenest.domain.Bid;
import com.notenest.domain.Music;
import com.notenest.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [반복 실행 멱등성 — 같은 논리 시각으로 두 번 실행]
 *
 * 고정 Clock({@link FixedClockTestConfig}) 덕에 잡을 같은 논리 시각에 두 번 호출해도, 두 번째
 * 실행에서 상태 전이·이메일 호출이 중복되지 않음을 고정한다. ACTION_PLAN 반복 실행 불변식.
 *
 * 멱등성의 근거:
 *  - 마감 잡은 status=0 만 조회 → 마감 후 status=1 이라 다음 사이클엔 대상에서 빠진다.
 *  - 결제 후속 잡은 PENDING 대상만, 현재 대기자 기준 한 사이클 1전이 → 같은 시각 재실행은 no-op.
 */
class AuctionBatchIdempotencyTest extends AuctionEndCharacterizationSupport {

    @Test
    @DisplayName("마감 잡: 같은 시각 2회 실행해도 status·PENDING·낙찰 메일이 중복되지 않는다")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void closeJob_runTwice_noDuplicateTransitionOrEmail() throws Exception {
        Music music = saveEndedMusic(now().minusDays(1));
        Bid bidA1 = saveBid(music, bidderA, 3000);
        Bid bidB1 = saveBid(music, bidderB, 2000);

        bidService.checkAuctionEnd();
        bidService.checkAuctionEnd(); // 같은 논리 시각 재실행

        Music after = musicRepository.findById(music.getMusicUuid()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(1);
        assertThat(status(bidA1)).isEqualTo("PENDING");
        assertThat(status(bidB1)).isEqualTo("FAILED");
        // 낙찰 성공 메일은 1순위에게 정확히 1회(재실행에도 중복 없음)
        verify(emailService, times(1)).sendBidSuccessToBidder(any(User.class), any(Music.class));
        verify(emailService, never()).sendAuctionFailureToComposer(any(User.class), any(Music.class));
    }

    @Test
    @DisplayName("결제 후속 잡(결제 완료): 같은 시각 2회 실행해도 COMPLETED·작곡가 메일이 중복되지 않는다")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void paymentJob_paid_runTwice_noDuplicateCompletionOrEmail() throws Exception {
        Music music = saveEndedMusic(now().minusDays(1)); // 결제기한(D+3) 전
        Bid bidA1 = saveBid(music, bidderA, 3000);
        savePayment(bidA1, "PAID");

        bidService.checkAuctionEnd();        // A PENDING
        bidService.checkPendingPayments();   // A → COMPLETED
        bidService.checkPendingPayments();   // 같은 시각 재실행

        assertThat(status(bidA1)).isEqualTo("COMPLETED");
        verify(emailService, times(1)).sendBidSuccessToComposer(any(User.class), any(Music.class));
        verify(emailService, times(1)).sendBidSuccessToBidder(any(User.class), any(Music.class));
    }

    @Test
    @DisplayName("결제 후속 잡(차순위 승계): 같은 시각 2회 실행해도 승계 전이·낙찰 메일이 중복되지 않는다")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void paymentJob_promotion_runTwice_noDuplicateTransitionOrEmail() throws Exception {
        // 1순위 기한(D+3) 만료, 2순위 기한(D+6) 전. A@3000(1순위), B@2000(2순위, 다른 사용자)
        Music music = saveEndedMusic(now().minusDays(4));
        Bid bidA1 = saveBid(music, bidderA, 3000);
        Bid bidB1 = saveBid(music, bidderB, 2000);

        bidService.checkAuctionEnd();        // A PENDING, B FAILED
        bidService.checkPendingPayments();   // A 기한만료 → FAILED, B 승계 PENDING
        bidService.checkPendingPayments();   // 같은 시각 재실행: B 는 D+6 전 → no-op

        assertThat(status(bidA1)).isEqualTo("FAILED");
        assertThat(status(bidB1)).isEqualTo("PENDING");
        // 낙찰 성공 메일 = A(최초) + B(승계) 각각 1회 → 총 2회(재실행에도 중복 없음)
        verify(emailService, times(2)).sendBidSuccessToBidder(any(User.class), any(Music.class));
        verify(emailService, never()).sendAuctionFailureToComposer(any(User.class), any(Music.class));

        Music after = musicRepository.findById(music.getMusicUuid()).orElseThrow();
        assertThat(after.isAuctionFailureEmailSent()).isFalse();
    }
}
