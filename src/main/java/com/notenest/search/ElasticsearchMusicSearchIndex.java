package com.notenest.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [NB5] {@link MusicSearchIndex} 의 Elasticsearch 구현. 매핑·분석기는 resources/elasticsearch/music-index.json.
 */
@Component
public class ElasticsearchMusicSearchIndex implements MusicSearchIndex {

    static final String INDEX_DEFINITION = "elasticsearch/music-index.json";
    private static final int BULK_CHUNK = 500;
    private static final int SCAN_PAGE = 1000;

    private final ElasticsearchClient client;
    private final String index;

    public ElasticsearchMusicSearchIndex(ElasticsearchClient client, @Value("${notenest.search.index}") String index) {
        this.client = client;
        this.index = index;
    }

    @Override
    public String name() {
        return index;
    }

    @Override
    public void ensureExists() {
        if (!exists()) {
            call(() -> {
                try {
                    create();
                } catch (ElasticsearchException e) {
                    // 동시에 다른 인스턴스가 만든 경우는 성공으로 본다
                    if (!"resource_already_exists_exception".equals(e.error().type())) {
                        throw e;
                    }
                }
                return null;
            });
        }
    }

    private boolean exists() {
        return call(() -> client.indices().exists(e -> e.index(index)).value());
    }

    @Override
    public void recreate() {
        call(() -> {
            if (client.indices().exists(e -> e.index(index)).value()) {
                client.indices().delete(d -> d.index(index));
            }
            create();
            return null;
        });
    }

    private void create() throws IOException {
        try (InputStream definition = new ClassPathResource(INDEX_DEFINITION).getInputStream()) {
            client.indices().create(c -> c.index(index).withJson(definition));
        }
    }

    // 쓰기 전에 매핑 있는 색인을 보장한다 — 색인이 없는 상태(새 ES, 누군가 삭제)에서 문서를 넣으면 Elasticsearch 가
    // 기본(dynamic) 매핑으로 색인을 자동 생성해 Nori·strict 매핑 없이 굳어 버린다. 로컬·통합 테스트 ES 는
    // action.auto_create_index=false 로 자동 생성 자체도 막는다(이중 방어). 확인 비용은 쓰기당 존재 조회 1회다.
    @Override
    public void upsert(MusicSearchDocument document) {
        ensureExists();
        call(() -> client.index(i -> i.index(index).id(document.musicId()).document(document)));
    }

    @Override
    public void upsertAll(List<MusicSearchDocument> documents) {
        ensureExists();
        bulk(documents.stream()
                .map(doc -> BulkOperation.of(o -> o.index(i -> i.index(index).id(doc.musicId()).document(doc))))
                .toList());
    }

    @Override
    public void delete(String musicId) {
        // 색인이 없으면 지울 것도 없다. 없는 문서 삭제는 결과가 not_found 일 뿐 예외가 아니다(멱등)
        if (!exists()) {
            return;
        }
        call(() -> client.delete(d -> d.index(index).id(musicId)));
    }

    @Override
    public void deleteAll(Collection<String> musicIds) {
        if (!exists()) {
            return;
        }
        bulk(musicIds.stream().map(id -> BulkOperation.of(o -> o.delete(d -> d.index(index).id(id)))).toList());
    }

    @Override
    public Map<String, String> sourceHashes() {
        return call(() -> {
            Map<String, String> hashes = new HashMap<>();
            List<FieldValue> after = null;
            while (true) {
                List<FieldValue> searchAfter = after;
                SearchResponse<HashOnly> response = client.search(s -> {
                    s.index(index).size(SCAN_PAGE)
                            .source(src -> src.filter(f -> f.includes("source_hash")))
                            .sort(so -> so.field(f -> f.field("music_id").order(SortOrder.Asc)));
                    if (searchAfter != null) {
                        s.searchAfter(searchAfter);
                    }
                    return s;
                }, HashOnly.class);
                List<Hit<HashOnly>> hits = response.hits().hits();
                for (Hit<HashOnly> hit : hits) {
                    hashes.put(hit.id(), hit.source() == null ? null : hit.source().sourceHash());
                }
                if (hits.size() < SCAN_PAGE) {
                    return hashes;
                }
                after = hits.get(hits.size() - 1).sort();
            }
        });
    }

    @Override
    public void refresh() {
        call(() -> client.indices().refresh(r -> r.index(index)));
    }

    private void bulk(List<BulkOperation> operations) {
        for (int from = 0; from < operations.size(); from += BULK_CHUNK) {
            List<BulkOperation> chunk = new ArrayList<>(operations.subList(from, Math.min(from + BULK_CHUNK, operations.size())));
            BulkResponse response = call(() -> client.bulk(b -> b.operations(chunk)));
            if (response.errors()) {
                // 삭제 대상이 이미 없던 경우(404)는 실패가 아니다
                BulkResponseItem failed = response.items().stream()
                        .filter(i -> i.error() != null && i.status() != 404).findFirst().orElse(null);
                if (failed != null) {
                    throw new SearchIndexException("색인 실패 id=" + failed.id() + " reason=" + failed.error().reason(), null);
                }
            }
        }
    }

    private <T> T call(IoCall<T> call) {
        try {
            return call.run();
        } catch (IOException | ElasticsearchException e) {
            throw new SearchIndexException("검색 색인 작업 실패 index=" + index + ": " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface IoCall<T> {
        T run() throws IOException;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HashOnly(@JsonProperty("source_hash") String sourceHash) {
    }
}
