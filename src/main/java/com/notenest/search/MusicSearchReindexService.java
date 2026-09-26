package com.notenest.search;

import com.notenest.repository.MusicRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * [NB5] MariaDB(원본) 전체를 읽어 검색 색인을 다시 만든다. 개발·통합 테스트용 전체 재색인이다(Phase 3).
 * 운영용 증분 갱신과 불일치 복구는 Phase 4 에서 추가한다.
 */
@Service
public class MusicSearchReindexService {

    private static final Logger log = LoggerFactory.getLogger(MusicSearchReindexService.class);

    private final MusicRepository musicRepository;
    private final MusicSearchIndexer indexer;

    public MusicSearchReindexService(MusicRepository musicRepository, MusicSearchIndexer indexer) {
        this.musicRepository = musicRepository;
        this.indexer = indexer;
    }

    public int rebuildFromDatabase() {
        long start = System.currentTimeMillis();
        List<MusicSearchDocument> documents = musicRepository.findAllSearchSources().stream()
                .map(MusicSearchDocument::from)
                .toList();
        indexer.recreateIndex();
        indexer.indexAll(documents);
        log.info("[SEARCH] rebuilt index={} documents={} elapsedMs={}", indexer.index(), documents.size(),
                System.currentTimeMillis() - start);
        return documents.size();
    }
}
