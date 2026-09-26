package com.notenest.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * [NB5] 검색 색인 생성·문서 적재. 매핑·분석기는 resources/elasticsearch/music-index.json.
 *
 * Phase 3 범위는 "전체 다시 만들기"다 — 고정 이름 색인을 지우고 새로 만든 뒤 적재하므로 그동안 검색이 비거나 503 이 된다.
 * 변경 시점 갱신(after-commit)·sourceHash 대조는 Phase 4, 새 색인 → 검증 → alias 교체는 P1 후보다.
 */
@Component
public class MusicSearchIndexer {

    static final String INDEX_DEFINITION = "elasticsearch/music-index.json";
    private static final int BULK_CHUNK = 500;

    private final ElasticsearchClient client;
    private final String index;

    public MusicSearchIndexer(ElasticsearchClient client, @Value("${notenest.search.index}") String index) {
        this.client = client;
        this.index = index;
    }

    public String index() {
        return index;
    }

    public void recreateIndex() {
        try {
            if (client.indices().exists(e -> e.index(index)).value()) {
                client.indices().delete(d -> d.index(index));
            }
            try (InputStream definition = new ClassPathResource(INDEX_DEFINITION).getInputStream()) {
                client.indices().create(c -> c.index(index).withJson(definition));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 문서를 _id = music_id 로 덮어쓴다(같은 문서를 다시 넣어도 결과가 같다). 끝나면 refresh 해 바로 검색되게 한다. */
    public void indexAll(List<MusicSearchDocument> documents) {
        try {
            for (int from = 0; from < documents.size(); from += BULK_CHUNK) {
                List<BulkOperation> operations = documents.subList(from, Math.min(from + BULK_CHUNK, documents.size())).stream()
                        .map(doc -> BulkOperation.of(o -> o.index(i -> i.index(index).id(doc.musicId()).document(doc))))
                        .toList();
                BulkResponse response = client.bulk(b -> b.operations(operations));
                if (response.errors()) {
                    BulkResponseItem failed = response.items().stream().filter(i -> i.error() != null).findFirst().orElseThrow();
                    throw new IllegalStateException("색인 실패 id=" + failed.id() + " reason=" + failed.error().reason());
                }
            }
            client.indices().refresh(r -> r.index(index));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
