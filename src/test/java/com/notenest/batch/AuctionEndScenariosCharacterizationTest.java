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
 * [특성화 테스트 — 현행(변경 전) 동작 고정 · 4개 표준 시나리오]
 *
 * ACTION_PLAN "AWS S3 도입 전" 1항의 네 시나리오를, 구현을 바꾸기 전에 현행 코드가
 * 실제로 어떤 상태 전이·이메일 호출을 하는지로 고정한다.
 *   1) 무입찰 종료
 *   2) 1순위 결제 완료
 *   3) 1순위 기한 만료 후 차순위(다른 사용자) 승계
 *   4) 최종 후보까지 실패
 *
 * 동일 사용자 1·2순위 엣지 케이스는 {@link AuctionEndSameBidderCharacterizationTest} 에 분리.
 * 실행 격리·픽스처 헬퍼는 {@link AuctionEndCharacterizationSupport} 참고.
 */
class AuctionEndScenariosCharacterizationTest extends AuctionEndCharacterizationSupport {

    @Test
    @DisplayName("현행 ①: 무입찰로 종료되면 종료 처리 후 작곡가에게 실패 메일 1회")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void noBids_endsAsFailure_composerNotifiedOnce() throws Exception {
        // given: 마감됐지만 입찰이 하나도 없는 곡
        Music music = saveEndedMusic(LocalDateTime.now().minusDays(1));

        // when
        bidService.processAuctionEnd(music.getMusicUuid());

        // then: 종료 처리 + 실패 메일 1회, 성공 메일류는 없음
        Music after = musicRepository.findById(music.getMusicUuid()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(1);
        assertThat(after.isAuctionFailureEmailSent()).isTrue();
        verify(emailService, times(1)).sendAuctionFailureToComposer(any(User.class), any(Music.class));
        verify(emailService, never()).sendBidSuccessToBidder(any(User.class), any(Music.class));
        verify(emailService, never()).sendBidSuccessToComposer(any(User.class), any(Music.class));
    }

    @Test
    @DisplayName("현행 ②: 1순위가 기한 내 결제 완료(PAID)면 COMPLETED 로 전이, 작곡가 성공 메일 1회")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void firstBidderPaid_transitionsToCompleted_composerSuccessMailOnce() throws Exception {
        // given: 마감됐고 결제기한(D+3)은 아직 안 지난 곡. A@3000(1순위) 결제 완료, B@2000(2순위)
        Music music = saveEndedMusic(LocalDateTime.now().minusDays(1));
        Bid bidA1 = saveBid(music, bidderA, 3000);
        Bid bidB1 = saveBid(music, bidderB, 2000);
        savePayment(bidA1, "PAID");

        // when
        bidService.processAuctionEnd(music.getMusicUuid());

        // then: 1순위 COMPLETED, 2순위 FAILED
        assertThat(status(bidA1)).isEqualTo("COMPLETED");
        assertThat(status(bidB1)).isEqualTo("FAILED");

        // then: 1순위에게 낙찰 성공 메일 1회 + 작곡가에게 성공(결제완료) 메일 1회, 실패 메일 없음
        verify(emailService, times(1)).sendBidSuccessToBidder(any(User.class), any(Music.class));
        verify(emailService, times(1)).sendBidSuccessToComposer(any(User.class), any(Music.class));
        verify(emailService, never()).sendAuctionFailureToComposer(any(User.class), any(Music.class));

        Music after = musicRepository.findById(music.getMusicUuid()).orElseThrow();
        assertThat(after.isAuctionFailureEmailSent()).isFalse();
    }

    @Test
    @DisplayName("현행 ③: 1순위 기한 만료 + 2순위가 다른 사용자면, 2순위를 PENDING 으로 승계한다")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void firstDeadlineExpired_differentSecondBidder_promotedToPending() throws Exception {
        // given: 1순위 결제기한(D+3)은 지났으나 2순위 기한(D+6)은 안 지난 곡. A@3000(1순위), B@2000(2순위, 다른 사용자)
        Music music = saveEndedMusic(LocalDateTime.now().minusDays(4));
        Bid bidA1 = saveBid(music, bidderA, 3000);
        Bid bidB1 = saveBid(music, bidderB, 2000);

        // when
        bidService.processAuctionEnd(music.getMusicUuid());

        // then: 1순위 FAILED(기한 만료), 2순위(다른 사용자) PENDING 으로 승계
        assertThat(status(bidA1)).isEqualTo("FAILED");
        assertThat(status(bidB1)).isEqualTo("PENDING");

        // then: 낙찰 성공 메일은 A(최초) + B(승계) 각각 → 총 2회. 아직 무산/결제완료 메일은 없음
        verify(emailService, times(2)).sendBidSuccessToBidder(any(User.class), any(Music.class));
        verify(emailService, never()).sendAuctionFailureToComposer(any(User.class), any(Music.class));
        verify(emailService, never()).sendBidSuccessToComposer(any(User.class), any(Music.class));

        Music after = musicRepository.findById(music.getMusicUuid()).orElseThrow();
        assertThat(after.isAuctionFailureEmailSent()).isFalse();
    }

    @Test
    @DisplayName("현행 ④: 1순위·2순위 모두 기한 만료면 최종 후보까지 실패 → 경매 무산 메일 1회")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void bothDeadlinesExpired_endsAsFailure_composerNotifiedOnce() throws Exception {
        // given: 1순위(D+3)·2순위(D+6) 결제기한이 모두 지난 곡. A@3000(1순위), B@2000(2순위, 다른 사용자), 결제 없음
        Music music = saveEndedMusic(LocalDateTime.now().minusDays(7));
        Bid bidA1 = saveBid(music, bidderA, 3000);
        Bid bidB1 = saveBid(music, bidderB, 2000);

        // when
        bidService.processAuctionEnd(music.getMusicUuid());

        // then: 두 입찰 모두 FAILED
        assertThat(status(bidA1)).isEqualTo("FAILED");
        assertThat(status(bidB1)).isEqualTo("FAILED");

        // then: A·B 승계 시도로 낙찰 성공 메일 2회, 최종 무산 메일 1회, 결제완료 메일 없음
        verify(emailService, times(2)).sendBidSuccessToBidder(any(User.class), any(Music.class));
        verify(emailService, times(1)).sendAuctionFailureToComposer(any(User.class), any(Music.class));
        verify(emailService, never()).sendBidSuccessToComposer(any(User.class), any(Music.class));

        Music after = musicRepository.findById(music.getMusicUuid()).orElseThrow();
        assertThat(after.isAuctionFailureEmailSent()).isTrue();
    }
}
