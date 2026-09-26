package com.notenest.search;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * [NB5] 검색 색인 쓰기·대조용 연산. 실제 구현은 {@link ElasticsearchMusicSearchIndex}, 기본 테스트는 실패를 주입할 수 있는 fake 를 쓴다.
 * 모든 쓰기는 _id = music_id 덮어쓰기라 같은 문서를 여러 번 넣어도 결과가 같다(멱등).
 * 실패는 {@link SearchIndexException} 으로 알린다.
 */
public interface MusicSearchIndex {

    String name();

    /** 색인이 없으면 매핑으로 만든다. */
    void ensureExists();

    /** 색인을 지우고 다시 만든다(전체 재색인용 — 그동안 검색이 비거나 503). */
    void recreate();

    void upsert(MusicSearchDocument document);

    void upsertAll(List<MusicSearchDocument> documents);

    void delete(String musicId);

    void deleteAll(Collection<String> musicIds);

    /** 색인의 전체 문서 id → source_hash. 대조 작업용. */
    Map<String, String> sourceHashes();

    /** 바로 검색되게 한다(전체 적재·대조 뒤). 단건 동기화는 기본 refresh 주기(1초)를 따른다. */
    void refresh();
}
