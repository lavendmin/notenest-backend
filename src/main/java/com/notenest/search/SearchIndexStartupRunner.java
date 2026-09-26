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

    public SearchIndexStartupRunner(MusicSearchReindexService reindexService) {
        this.reindexService = reindexService;
    }

    @Override
    public void run(ApplicationArguments args) {
        reindexService.rebuildFromDatabase();
    }
}
