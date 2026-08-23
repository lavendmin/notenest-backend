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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [하이브리드 정책 검증 — 동일 사용자 1·2순위 엣지 케이스]
 *
 * Step 1~2 에서 이 픽스처의 현행 동작은 "2순위가 1순위와 같은 사용자면 3순위의 다른 사용자를
 * 보지 않고 경매 무산"이었다(AuctionEndSameBidderCharacterizationTest, 삭제됨). Step 3 에서
 * 하이브리드 정책(동일인 하위 입찰은 건너뛰고 다음 "고유 사용자"를 승계)을 적용하면서 이 테스트는
 * 승계가 실제로 일어나는 새 동작을 고정한다.
 *
 * 이 파일의 git 히스토리(삭제된 characterization → 이 promotion)가 "무엇이 어떻게 달라졌는가"의
 * 회귀 증거다. 실행 격리·픽스처 헬퍼는 {@link AuctionEndCharacterizationSupport} 참고.
 */
class AuctionEndSameBidderPromotionTest extends AuctionEndCharacterizationSupport {

    @Test
    @DisplayName("하이브리드: 2순위가 1순위와 같은 사용자면 건너뛰고, 다음 고유 사용자(3순위)를 PENDING 으로 승계한다")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void secondBidSameUser_isSkipped_andNextUniqueUserIsPromoted() throws Exception {
        // given: 1순위 결제기한(D+3)은 지났으나 2순위 기한(D+6)은 안 지난 곡.
        //        입찰 = A@3000(1순위), A@2500(2순위=동일인), B@2000(다음 고유 사용자)
        Music music = saveEndedMusic(LocalDateTime.now().minusDays(4));
        Bid bidA1 = saveBid(music, bidderA, 3000);
        Bid bidA2 = saveBid(music, bidderA, 2500);
        Bid bidB1 = saveBid(music, bidderB, 2000);

        // when: 마감 잡(최초 낙찰자 선정) → 결제 후속 잡 1회(1순위 기한 만료 → 차순위 승계)
        bidService.processAuctionEnd(music.getMusicUuid());
        bidService.processPaymentFollowUp(music.getMusicUuid());

        // then: 곡은 종료 처리(status 0→1)
        Music after = musicRepository.findById(music.getMusicUuid()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(1);

        // then: 1순위(A)는 기한 만료로 FAILED, 동일인 하위 입찰(A@2500)도 FAILED,
        //       B(다음 고유 사용자)는 건너뛰지 않고 PENDING 으로 승계된다.
        assertThat(status(bidA1)).isEqualTo("FAILED");
        assertThat(status(bidA2)).isEqualTo("FAILED");
        assertThat(status(bidB1)).isEqualTo("PENDING");

        // then: 아직 2순위 기한(D+6) 전이므로 경매 무산이 아니다.
        assertThat(after.isAuctionFailureEmailSent()).isFalse();
        verify(emailService, never()).sendAuctionFailureToComposer(any(User.class), any(Music.class));

        // then: 낙찰 성공 메일은 A(최초) + B(승계) 각각 1회 → 총 2회. 결제완료(작곡가) 메일은 없음.
        ArgumentCaptor<User> bidderCaptor = ArgumentCaptor.forClass(User.class);
        verify(emailService, times(2)).sendBidSuccessToBidder(bidderCaptor.capture(), any(Music.class));
        List<User> notified = bidderCaptor.getAllValues();
        assertThat(notified).extracting(User::getUserUUID)
                .containsExactlyInAnyOrder(bidderA.getUserUUID(), bidderB.getUserUUID());
        verify(emailService, never()).sendBidSuccessToComposer(any(User.class), any(Music.class));
    }
}
