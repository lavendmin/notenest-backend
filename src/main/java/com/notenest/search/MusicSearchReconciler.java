package com.notenest.search;

import com.notenest.repository.MusicRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * [NB5] DB(원본)와 검색 색인의 결정적 대조·복구.
 *
 * 1) 색인의 전체 id → source_hash 를 읽고, 2) DB 전체로 문서·해시를 다시 계산해
 *  - 누락(DB 에만 있음) → upsert, 불일치(해시 다름) → upsert, 잔존(색인에만 있음, 삭제된 곡) → 삭제.
 * 복구 후 같은 대조를 다시 돌리면 변경 건수가 0 이어야 한다(멱등).
 *
 * 동기화와 경합하지 않도록 {@link MusicSearchSynchronizer#submit} 로 같은 쓰기 스레드에서 실행한다(스케줄러가 그렇게 부른다).
 * 대조 중 커밋된 변경은 그 뒤에 줄 선 동기화 작업이 최신 행으로 다시 반영한다.
 * 한계: DB·색인 전체를 메모리에 올린다(곡 1만 건 규모 기준). 대규모 카탈로그는 구간별 대조가 필요하다.
 */
@Service
public class MusicSearchReconciler {

    private static final Logger log = LoggerFactory.getLogger(MusicSearchReconciler.class);

    public record Result(int dbDocuments, int indexedBefore, int missing, int changed, int stale, long elapsedMillis) {

        public int totalChanges() {
            return missing + changed + stale;
        }
    }

    private final MusicRepository musicRepository;
    private final MusicSearchIndex index;

    public MusicSearchReconciler(MusicRepository musicRepository, MusicSearchIndex index) {
        this.musicRepository = musicRepository;
        this.index = index;
    }

    public Result reconcile() {
        long start = System.currentTimeMillis();
        index.ensureExists();
        Map<String, String> indexed = index.sourceHashes();
        Map<String, MusicSearchDocument> db = musicRepository.findAllSearchSources().stream()
                .map(MusicSearchDocument::from)
                .collect(Collectors.toMap(MusicSearchDocument::musicId, Function.identity()));

        List<MusicSearchDocument> upserts = new ArrayList<>();
        int missing = 0;
        int changed = 0;
        for (MusicSearchDocument doc : db.values()) {
            if (!indexed.containsKey(doc.musicId())) {
                missing++;
                upserts.add(doc);
            } else if (!Objects.equals(indexed.get(doc.musicId()), doc.sourceHash())) {
                changed++;
                upserts.add(doc);
            }
        }
        List<String> stale = indexed.keySet().stream().filter(id -> !db.containsKey(id)).sorted().toList();

        if (!upserts.isEmpty()) {
            index.upsertAll(upserts);
        }
        if (!stale.isEmpty()) {
            index.deleteAll(stale);
        }
        if (!upserts.isEmpty() || !stale.isEmpty()) {
            index.refresh();
        }
        Result result = new Result(db.size(), indexed.size(), missing, changed, stale.size(), System.currentTimeMillis() - start);
        log.info("[SEARCH-RECONCILE] index={} db={} indexedBefore={} missing={} changed={} stale={} elapsedMs={}",
                index.name(), result.dbDocuments(), result.indexedBefore(), missing, changed, stale.size(), result.elapsedMillis());
        return result;
    }
}
