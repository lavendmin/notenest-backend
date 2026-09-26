package com.notenest.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [NB5] 검색 동기화 결과 기록 — 성공·재시도 후 성공·최종 실패 건수와 최근 실패 100건. 최종 실패는 ERROR 로그로 남긴다.
 * '실패'는 "반영을 확인하지 못함"이다. 거부(4xx·쓰기 차단)는 반영되지 않았지만, 응답 타임아웃은 요청이 이미 전달돼 나중에 반영될 수
 * 있다(Phase 4 통합 테스트에서 실측). 그래서 실패 기록은 복구 대상 목록이 아니라 신호이고, 수렴은 원본 기준 대조가 보장한다.
 * 실패한 문서는 대조 작업(MusicSearchReconciler)이 원본과 비교해 복구한다. 영속 큐(Outbox)는 두지 않는다(P0 범위).
 */
@Component
public class SearchSyncRecorder {

    private static final Logger log = LoggerFactory.getLogger(SearchSyncRecorder.class);
    private static final int RECENT_LIMIT = 100;

    public record Failure(UUID musicUuid, String reason, int attempts, String cause, Instant at) {
    }

    public record Snapshot(long synced, long recoveredAfterRetry, long failed, List<Failure> recentFailures) {
    }

    private final AtomicLong synced = new AtomicLong();
    private final AtomicLong recoveredAfterRetry = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final Deque<Failure> recent = new ArrayDeque<>();

    void synced(UUID musicUuid, String reason, int attempts) {
        synced.incrementAndGet();
        if (attempts > 1) {
            recoveredAfterRetry.incrementAndGet();
            log.warn("[SEARCH-SYNC] recovered musicUuid={} reason={} attempts={}", musicUuid, reason, attempts);
        }
    }

    void failed(UUID musicUuid, String reason, int attempts, Throwable cause) {
        failed.incrementAndGet();
        String message = cause == null ? null : cause.toString();
        synchronized (recent) {
            if (recent.size() == RECENT_LIMIT) {
                recent.removeFirst();
            }
            recent.addLast(new Failure(musicUuid, reason, attempts, message, Instant.now()));
        }
        log.error("[SEARCH-SYNC] unconfirmed musicUuid={} reason={} attempts={} cause={} — 반영 여부 미확인(타임아웃이면 늦게 반영될 수 있음), 대조 작업이 원본에 맞춘다",
                musicUuid, reason, attempts, message);
    }

    public Snapshot snapshot() {
        synchronized (recent) {
            return new Snapshot(synced.get(), recoveredAfterRetry.get(), failed.get(), List.copyOf(recent));
        }
    }
}
