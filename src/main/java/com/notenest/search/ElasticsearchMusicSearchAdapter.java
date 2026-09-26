package com.notenest.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.notenest.dto.MusicSummaryDTO;
import com.notenest.repository.MusicListCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * [NB5] {@link MusicSearchPort} 의 Elasticsearch 구현. 문서의 _source 로 목록 DTO 를 만들어 DB 를 다시 읽지 않는다.
 *
 * 장애 처리: 연결 실패·타임아웃(IOException)과 서버 오류(색인 없음 포함, ElasticsearchException)는
 * {@link SearchUnavailableException} 으로 바꿔 503 으로 응답하게 한다. 그 밖의 예외(프로그램 오류)는 감싸지 않는다.
 */
@Component
public class ElasticsearchMusicSearchAdapter implements MusicSearchPort {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchMusicSearchAdapter.class);

    private final ElasticsearchClient client;
    private final MusicSearchQueryFactory queryFactory;
    private final String index;

    public ElasticsearchMusicSearchAdapter(ElasticsearchClient client, MusicSearchQueryFactory queryFactory,
                                           @Value("${notenest.search.index}") String index) {
        this.client = client;
        this.queryFactory = queryFactory;
        this.index = index;
    }

    @Override
    public Page<MusicSummaryDTO> search(MusicListCondition condition, String sortBy, Pageable pageable) {
        SearchResponse<MusicSearchDocument> response;
        try {
            response = client.search(queryFactory.build(index, condition, sortBy, pageable), MusicSearchDocument.class);
        } catch (IOException | ElasticsearchException e) {
            log.warn("[SEARCH] unavailable index={} cause={}", index, e.toString());
            throw new SearchUnavailableException("검색 엔진에 연결할 수 없습니다", e);
        }
        List<MusicSummaryDTO> content = response.hits().hits().stream()
                .map(Hit::source)
                .map(MusicSearchDocument::toSummary)
                .toList();
        long total = response.hits().total() == null ? content.size() : response.hits().total().value();
        Pageable meta = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), queryFactory.sortMeta(sortBy));
        return new PageImpl<>(content, meta, total);
    }
}
