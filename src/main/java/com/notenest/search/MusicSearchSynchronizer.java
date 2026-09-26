package com.notenest.search;

import com.notenest.repository.MusicRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * [NB5] 곡 변경 → 검색 문서 동기화.
 *
 * 실행 시점 계약(호출 쪽 트랜잭션 경계가 제각각이라 명시한다):
 *  - 활성 트랜잭션 안에서 발행되면 <b>커밋 후</b> 실행한다. 롤백되면 실행하지 않는다(AFTER_COMMIT).
 *  - 트랜잭션 없이 발행되면(저장 메서드가 자체 트랜잭션으로 커밋하고 반환한 뒤) <b>즉시</b> 실행한다(fallbackExecution).
 *
 * 실행 방식: 전용 단일 쓰기 스레드(큐 10,000)에 넘기고 요청 스레드는 기다리지 않는다. 대조·전체 재색인도 {@link #submit} 로 같은
 * 스레드에서 돌려 색인 쓰기를 한 줄로 세운다. 각 작업은 실행 시점의 커밋된 행을 다시 읽으므로 순서가 섞여도 최신 상태가 남는다.
 * notenest.search.sync.async=false 면 호출 스레드에서 바로 실행한다(테스트용).
 *
 * 실패: 색인 오류는 최대 max-attempts 회(기본 3) 재시도하고, 그래도 실패하면 기록만 한다. DB 저장은 이미 성공했으므로 쓰기 요청에
 * 오류를 돌려주지 않는다. 큐가 가득 차거나 프로세스가 중단돼 잃은 동기화도 대조 작업이 복구한다.
 */
@Component
public class MusicSearchSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(MusicSearchSynchronizer.class);

    public enum Outcome { UPSERTED, DELETED }

    private final MusicRepository musicRepository;
    private final MusicSearchIndex index;
    private final SearchSyncRecorder recorder;
    private final int maxAttempts;
    private final long backoffMillis;
    private final Executor executor;
    private final ThreadPoolTaskExecutor ownedPool;

    public MusicSearchSynchronizer(MusicRepository musicRepository, MusicSearchIndex index, SearchSyncRecorder recorder,
                                   @Value("${notenest.search.sync.async:true}") boolean async,
                                   @Value("${notenest.search.sync.max-attempts:3}") int maxAttempts,
                                   @Value("${notenest.search.sync.backoff-millis:200}") long backoffMillis) {
        this.musicRepository = musicRepository;
        this.index = index;
        this.recorder = recorder;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMillis = backoffMillis;
        if (async) {
            ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
            pool.setCorePoolSize(1);
            pool.setMaxPoolSize(1);
            pool.setQueueCapacity(10_000);
            pool.setThreadNamePrefix("search-sync-");
            pool.setWaitForTasksToCompleteOnShutdown(true);
            pool.setAwaitTerminationSeconds(10);
            pool.initialize();
            this.ownedPool = pool;
            this.executor = pool;
        } else {
            this.ownedPool = null;
            this.executor = new SyncTaskExecutor();
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMusicChanged(MusicSearchChangedEvent event) {
        try {
            executor.execute(() -> syncWithRetry(event.musicUuid(), event.reason()));
        } catch (RejectedExecutionException e) {
            recorder.failed(event.musicUuid(), event.reason(), 0, e);
        }
    }

    /** 대조·재색인 같은 색인 작업을 동기화와 같은 쓰기 스레드에서 실행한다. */
    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, executor);
    }

    /** 원본 한 건을 다시 읽어 색인에 반영한다(재시도 없음). 대조 작업과 테스트가 쓴다. */
    public Outcome syncOnce(UUID musicUuid) {
        Optional<MusicSearchSource> source = musicRepository.findSearchSource(musicUuid);
        if (source.isPresent()) {
            index.upsert(MusicSearchDocument.from(source.get()));
            return Outcome.UPSERTED;
        }
        index.delete(musicUuid.toString());
        return Outcome.DELETED;
    }

    void syncWithRetry(UUID musicUuid, String reason) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                syncOnce(musicUuid);
                recorder.synced(musicUuid, reason, attempt);
                return;
            } catch (SearchIndexException e) {
                last = e;
                log.debug("[SEARCH-SYNC] attempt {} failed musicUuid={} cause={}", attempt, musicUuid, e.toString());
                if (attempt < maxAttempts) {
                    sleep(backoffMillis * attempt);
                }
            } catch (RuntimeException e) {
                // DB 조회 실패 등 — 재시도 대상이 아니다. 쓰기 스레드를 죽이지 않고 기록만 한다.
                recorder.failed(musicUuid, reason, attempt, e);
                return;
            }
        }
        recorder.failed(musicUuid, reason, maxAttempts, last);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownedPool != null) {
            ownedPool.shutdown();
        }
    }
}
