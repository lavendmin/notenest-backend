package com.notenest.search;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * [NB5] notenest.search.reindex-on-startup=true 일 때만 기동 직후 전체 재색인한다(기본 false — 로컬 측정·시연용).
 */
@Component
@ConditionalOnProperty(name = "notenest.search.reindex-on-startup", havingValue = "true")
public class SearchIndexStartupRunner implements ApplicationRunner {

    private final MusicSearchReindexService reindexService;
    private final MusicSearchSynchronizer synchronizer;

    public SearchIndexStartupRunner(MusicSearchReindexService reindexService, MusicSearchSynchronizer synchronizer) {
        this.reindexService = reindexService;
        this.synchronizer = synchronizer;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 동기화와 같은 쓰기 스레드에서 실행하고 끝날 때까지 기다린다
        synchronizer.submit(reindexService::rebuildFromDatabase).join();
    }
}
