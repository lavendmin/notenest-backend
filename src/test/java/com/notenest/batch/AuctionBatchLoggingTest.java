package com.notenest.batch;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.notenest.domain.Bid;
import com.notenest.domain.Music;
import com.notenest.service.BidServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [잡별 로그 계약 검증]
 *
 * 마감 잡·결제 후속 잡이 각각 targets/processed/failed/elapsedMs 를 남기는지, 그리고 그 수치가
 * 대상 필터(마감=status0, 결제=PENDING)와 일치하는지 고정한다. 이 로그는 Step 5 성능 스냅샷의
 * 측정 근거이므로 형식이 회귀로 깨지지 않도록 잠근다.
 *
 * 실행 격리·픽스처 헬퍼는 {@link AuctionEndCharacterizationSupport} 참고.
 */
class AuctionBatchLoggingTest extends AuctionEndCharacterizationSupport {

    @Test
    @DisplayName("마감 잡·결제 후속 잡은 targets/processed/failed/elapsedMs 를 남기고, 대상 수는 필터와 일치한다")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void bothJobs_logStructuredCounts_matchingTargetFilters() throws Exception {
        // given: 마감 대기(status=0, 종료 경과) 곡 2개 + 이미 마감·정산된 곡 1개(status=1, COMPLETED 입찰)
        Music open1 = saveEndedMusic(now().minusDays(1));
        saveBid(open1, bidderA, 3000);
        Music open2 = saveEndedMusic(now().minusDays(1));
        saveBid(open2, bidderA, 4000);
        Music alreadyClosed = saveEndedMusic(now().minusDays(1));
        alreadyClosed.setStatus(1);
        musicRepository.save(alreadyClosed);
        Bid settled = saveBid(alreadyClosed, bidderB, 5000);
        settled.setStatus("COMPLETED");
        bidRepository.save(settled);

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            // when: 마감 잡 — status=0 곡 2개만 대상(이미 마감된 곡 제외)
            bidService.checkAuctionEnd();

            // then: 마감 잡 요약 로그
            String closeSummary = lastMessageContaining(appender, "job=auction-close");
            assertThat(closeSummary)
                    .contains("targets=2")
                    .contains("processed=2")
                    .contains("failed=0")
                    .contains("elapsedMs=");

            // when: 결제 후속 잡 — 방금 마감된 2곡의 PENDING 낙찰자만 대상(COMPLETED 곡 제외)
            bidService.checkPendingPayments();

            // then: 결제 후속 잡 요약 로그
            String paymentSummary = lastMessageContaining(appender, "job=payment-followup");
            assertThat(paymentSummary)
                    .contains("targets=2")
                    .contains("processed=2")
                    .contains("failed=0")
                    .contains("elapsedMs=");
        } finally {
            detachAppender(appender);
        }
    }

    // --- 로그 캡처 헬퍼 ---

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(BidServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(BidServiceImpl.class);
        logger.detachAppender(appender);
    }

    private String lastMessageContaining(ListAppender<ILoggingEvent> appender, String needle) {
        List<ILoggingEvent> events = appender.list;
        Optional<String> match = events.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(needle))
                .reduce((first, second) -> second); // 마지막 매칭
        assertThat(match).as("log line containing '%s'", needle).isPresent();
        return match.get();
    }
}
