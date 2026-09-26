package com.notenest.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * [NB5] 주기 대조 — 기본 10분 간격, 기동 5분 뒤 첫 실행(notenest.search.reconcile.*). 스케줄링 전체가 꺼져 있으면
 * (notenest.scheduling.enabled=false) 돌지 않는다. 색인 쓰기 스레드에서 실행해 동기화와 경합하지 않는다.
 * 검색 엔진 장애 중에는 실패를 기록하고 다음 주기에 다시 시도한다.
 */
@Component
@ConditionalOnProperty(name = "notenest.search.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class SearchReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(SearchReconcileScheduler.class);

    private final MusicSearchSynchronizer synchronizer;
    private final MusicSearchReconciler reconciler;

    public SearchReconcileScheduler(MusicSearchSynchronizer synchronizer, MusicSearchReconciler reconciler) {
        this.synchronizer = synchronizer;
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${notenest.search.reconcile.fixed-delay:PT10M}",
            initialDelayString = "${notenest.search.reconcile.initial-delay:PT5M}")
    public void reconcile() {
        synchronizer.submit(reconciler::reconcile).whenComplete((result, error) -> {
            if (error != null) {
                log.warn("[SEARCH-RECONCILE] failed — 다음 주기에 다시 시도한다: {}", error.toString());
            }
        });
    }
}
