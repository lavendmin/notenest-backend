package com.notenest.batch;

import com.notenest.domain.Bid;
import com.notenest.domain.Music;
import com.notenest.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [특성화 테스트 — 현행(변경 전) 동작 고정 · 착수 게이트 픽스처]
 *
 * 목적: 하이브리드 정책으로 구현을 바꾸기 "전에", 현재 코드가 실제로
 *       "2등 입찰자가 1등과 같은 사용자면, 그 아래 다른 사용자(3순위)를 보지 않고 경매 무산"
 *       으로 끝난다는 것을 자동 증명한다.
 *
 * 이 테스트가 GREEN 이라는 것은 "지금 코드가 이렇게 동작한다"는 뜻일 뿐, 이 동작이
 * 바람직하다는 뜻이 아니다. Step 3~4 에서 하이브리드(사용자별 최고가 dedup → 서로 다른
 * 상위 2명 승계)로 바꾸면 이 테스트의 기대값이 바뀌어야 하며, 그 diff 가 회귀 증거가 된다.
 *
 * 실행 격리·픽스처 헬퍼는 {@link AuctionEndCharacterizationSupport} 참고.
 */
class AuctionEndSameBidderCharacterizationTest extends AuctionEndCharacterizationSupport {

    @Test
    @DisplayName("현행: 2순위가 1순위와 같은 사용자면, 다른 사용자(3순위)를 승계하지 않고 경매 무산으로 끝난다")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void secondBidderSameAsFirst_currentlyEndsAsFailure_withoutPromotingThirdUniqueUser() throws Exception {
        // given: 마감·결제기한(D+3)이 모두 지난 곡. 입찰 = A@3000(1순위), A@2500(2순위), B@2000(3순위, 유일한 다른 사용자)
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
}
