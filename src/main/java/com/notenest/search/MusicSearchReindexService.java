package com.notenest.search;

import com.notenest.repository.MusicRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * [NB5] MariaDB(원본) 전체를 읽어 검색 색인을 새로 만든다(개발·통합 테스트·기동 시 옵션).
 * 고정 이름 색인을 지우고 다시 만드므로 그동안 검색이 비거나 503 이 된다 — 운영 복구는 무중단인 대조 작업을 쓰고,
 * 새 색인 → 검증 → alias 교체는 P1 후보다.
 */
@Service
public class MusicSearchReindexService {

    private static final Logger log = LoggerFactory.getLogger(MusicSearchReindexService.class);

    private final MusicRepository musicRepository;
    private final MusicSearchIndex index;

    public MusicSearchReindexService(MusicRepository musicRepository, MusicSearchIndex index) {
        this.musicRepository = musicRepository;
        this.index = index;
    }

    public int rebuildFromDatabase() {
        long start = System.currentTimeMillis();
        List<MusicSearchDocument> documents = musicRepository.findAllSearchSources().stream()
                .map(MusicSearchDocument::from)
                .toList();
        index.recreate();
        index.upsertAll(documents);
        index.refresh();
        log.info("[SEARCH] rebuilt index={} documents={} elapsedMs={}", index.name(), documents.size(),
                System.currentTimeMillis() - start);
        return documents.size();
    }
}
