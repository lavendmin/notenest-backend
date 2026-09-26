package com.notenest.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * [NB5] 기동 시 검색 색인 부트스트랩 — 새(빈) Elasticsearch 에서도 검색이 곧바로 원본과 맞춰지게 한다.
 *
 * 모든 싱글턴이 만들어진 직후(웹 서버가 요청을 받기 전) 색인 쓰기 큐 <b>맨 앞</b>에 작업 하나를 넣는다.
 *  - 기본: 대조(MusicSearchReconciler) — 색인이 없으면 올바른 매핑(Nori·strict)으로 만들고, 누락·불일치·잔존을 원본에 맞춘다.
 *    빈 ES 면 전체를 채우고, 이미 맞는 색인이면 변경 0 으로 끝난다(무중단).
 *  - notenest.search.reindex-on-startup=true: 전체 재색인(색인 재생성 — 그동안 검색 503).
 * 이후 곡 변경 동기화는 같은 큐에서 이 작업 뒤에 실행된다.
 *
 * 앱 기동을 막지 않는다: 쓰기 큐에서 비동기로 돌고, Elasticsearch 에 닿지 못하면 경고만 남긴다(검색어 경로는 503, 목록은 정상).
 * 그 뒤 첫 곡 변경 동기화가 색인을 만들고, 주기 대조(기본 기동 5분 후)가 나머지를 채운다.
 */
@Component
@ConditionalOnProperty(name = "notenest.search.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class SearchIndexBootstrap implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexBootstrap.class);

    public enum State { NOT_STARTED, RUNNING, DONE, FAILED }

    private final MusicSearchSynchronizer synchronizer;
    private final MusicSearchReconciler reconciler;
    private final MusicSearchReindexService reindexService;
    private final boolean rebuild;
    private final AtomicReference<State> state = new AtomicReference<>(State.NOT_STARTED);

    public SearchIndexBootstrap(MusicSearchSynchronizer synchronizer, MusicSearchReconciler reconciler,
                                MusicSearchReindexService reindexService,
                                @Value("${notenest.search.reindex-on-startup:false}") boolean rebuild) {
        this.synchronizer = synchronizer;
        this.reconciler = reconciler;
        this.reindexService = reindexService;
        this.rebuild = rebuild;
    }

    @Override
    public void afterSingletonsInstantiated() {
        start();
    }

    /** 부트스트랩 작업을 쓰기 큐에 넣는다. 결과는 {@link #state()} 와 로그로 알린다. */
    public CompletableFuture<Object> start() {
        state.set(State.RUNNING);
        String mode = rebuild ? "rebuild" : "reconcile";
        return synchronizer.<Object>submit(() -> rebuild ? reindexService.rebuildFromDatabase() : reconciler.reconcile())
                .whenComplete((result, error) -> {
                    if (error != null) {
                        state.set(State.FAILED);
                        log.warn("[SEARCH-BOOTSTRAP] {} failed — 앱은 계속 동작한다(검색어 경로 503, 목록 정상). "
                                + "첫 곡 변경 동기화와 주기 대조가 다시 시도한다: {}", mode, error.toString());
                    } else {
                        state.set(State.DONE);
                        log.info("[SEARCH-BOOTSTRAP] {} done: {}", mode, result);
                    }
                });
    }

    public State state() {
        return state.get();
    }
}
