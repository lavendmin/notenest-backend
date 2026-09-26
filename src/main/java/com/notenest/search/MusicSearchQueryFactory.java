package com.notenest.search;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonData;
import com.notenest.repository.MusicListCondition;
import com.notenest.repository.MusicListSort;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * [NB5] 검색어 경로의 Elasticsearch 질의 조립 — ADR-001 에서 채택한 Phase 2 스파이크 설정(scripts/nb5/phase2/nb5_query.py)을
 * 그대로 옮겼다. 값은 qrels v1 결과를 보고 조정하지 않았다.
 *
 * 관련도(should):
 *  - multi_match best_fields(tie_breaker 0.3): 제목·판매자 3 > 부제 2 > 태그 1.5 > 장르·설명 1 (결정 1 — 설명은 신규 대상, 낮은 가중치)
 *  - 정확 일치: 공백 제거·소문자 제목 또는 판매자 닉네임이 검색어와 같으면 +100 — 두 경우 같은 값(결정 5)
 *  - 한글 장르명: 검색어 어절이 별칭이면 해당 장르 코드 곡에 +2 (결정 2)
 * 필터(점수 무관): 진행 중, 장르(대소문자 무시 일치), 해시태그(태그별 부분 일치 AND — 기존 LIKE 계약), 가격(현재가·없으면 시작가),
 *  BPM 포함 범위, 키 정확 일치.
 * 정렬: 관련도 = _score → created_at → music_id / 명시 정렬 = 요청 키 → _score → music_id (안정적 tie-break).
 */
@Component
public class MusicSearchQueryFactory {

    public static final String RELEVANCE = "relevance";
    /** Elasticsearch index.max_result_window 기본값. from + size 가 이를 넘는 페이지는 400. */
    public static final int MAX_RESULT_WINDOW = 10_000;

    static final double TITLE_WEIGHT = 3.0;
    static final double SELLER_WEIGHT = 3.0;
    static final double SUBTITLE_WEIGHT = 2.0;
    static final double HASHTAG_WEIGHT = 1.5;
    static final double GENRE_WEIGHT = 1.0;
    static final double DETAILS_WEIGHT = 1.0;
    static final float EXACT_BOOST = 100f;
    static final float GENRE_ALIAS_BOOST = 2f;
    static final double TIE_BREAKER = 0.3;

    /** 한글 장르명 → 저장 코드 (결정 2). 저장 코드는 원 프론트엔드의 majorGenre 값이다. */
    static final Map<String, String> GENRE_ALIASES = Map.of(
            "발라드", "balad", "힙합", "hiphop", "트로트", "trot", "팝", "pop", "케이팝", "pop");

    public SearchRequest build(String index, MusicListCondition condition, String sortBy, Pageable pageable) {
        long from = pageable.getOffset();
        if (from + pageable.getPageSize() > MAX_RESULT_WINDOW) {
            throw new InvalidSearchRequestException(
                    "검색 결과는 " + MAX_RESULT_WINDOW + "번째까지만 페이지로 볼 수 있습니다. 검색어나 필터를 좁혀 주세요.");
        }
        String term = condition.searchTerm().strip();
        String primary = primarySort(sortBy);
        return SearchRequest.of(s -> s
                .index(index)
                .from((int) from)
                .size(pageable.getPageSize())
                .trackTotalHits(t -> t.enabled(true))
                .trackScores(!primary.equals(RELEVANCE))
                .query(q -> q.bool(b -> b
                        .filter(filters(condition))
                        .should(relevance(term))
                        .minimumShouldMatch("1")))
                .sort(sorts(primary)));
    }

    /** 응답 Page 메타데이터(sort)용 — 비검색 경로와 같은 표현을 쓰고, 관련도순만 _score 를 앞에 둔다. */
    public Sort sortMeta(String sortBy) {
        if (primarySort(sortBy).equals(RELEVANCE)) {
            return Sort.by(Sort.Order.desc("_score"), Sort.Order.desc("createdAt"));
        }
        return MusicListSort.toSort(sortBy);
    }

    static String primarySort(String sortBy) {
        if (sortBy == null || RELEVANCE.equals(sortBy)) {
            return RELEVANCE;
        }
        return ("price".equals(sortBy) || "like".equals(sortBy)) ? sortBy : "latest";
    }

    static String compact(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    static List<String> genreCodes(String term) {
        TreeSet<String> codes = new TreeSet<>();
        for (String token : term.split("\\s+")) {
            String code = GENRE_ALIASES.get(token);
            if (code != null) {
                codes.add(code);
            }
        }
        return List.copyOf(codes);
    }

    private List<Query> relevance(String term) {
        List<Query> should = new ArrayList<>();
        should.add(Query.of(q -> q.multiMatch(m -> m
                .query(term)
                .type(TextQueryType.BestFields)
                .tieBreaker(TIE_BREAKER)
                .fields("title^" + TITLE_WEIGHT, "seller^" + SELLER_WEIGHT, "subtitle^" + SUBTITLE_WEIGHT,
                        "hashtag^" + HASHTAG_WEIGHT, "genre^" + GENRE_WEIGHT, "details^" + DETAILS_WEIGHT))));
        should.add(exact("title.compact", compact(term)));
        should.add(exact("seller.compact", compact(term)));
        for (String code : genreCodes(term)) {
            should.add(Query.of(q -> q.constantScore(c -> c
                    .filter(f -> f.term(t -> t.field("genre").value(code)))
                    .boost(GENRE_ALIAS_BOOST))));
        }
        return should;
    }

    private static Query exact(String field, String value) {
        return Query.of(q -> q.constantScore(c -> c
                .filter(f -> f.term(t -> t.field(field).value(value)))
                .boost(EXACT_BOOST)));
    }

    private List<Query> filters(MusicListCondition c) {
        List<Query> filters = new ArrayList<>();
        filters.add(Query.of(q -> q.term(t -> t.field("status").value(0))));
        if (StringUtils.hasText(c.majorGenre())) {
            filters.add(Query.of(q -> q.term(t -> t.field("genre").value(c.majorGenre()))));
        }
        if (StringUtils.hasText(c.hashtags())) {
            for (String tag : c.hashtags().split(",")) {
                if (StringUtils.hasText(tag)) {
                    String pattern = "*" + escapeWildcard(tag.strip()) + "*";
                    filters.add(Query.of(q -> q.wildcard(w -> w.field("hashtag.raw").value(pattern).caseInsensitive(true))));
                }
            }
        }
        if (c.minPrice() != null || c.maxPrice() != null) {
            filters.add(range("price", c.minPrice(), c.maxPrice()));
        }
        if (c.bpmMin() != null || c.bpmMax() != null) {
            filters.add(range("bpm", c.bpmMin(), c.bpmMax()));
        }
        if (c.musicalKey() != null) {
            filters.add(Query.of(q -> q.term(t -> t.field("musical_key").value(c.musicalKey().name()))));
        }
        return filters;
    }

    private static Query range(String field, Number min, Number max) {
        return Query.of(q -> q.range(r -> {
            r.field(field);
            if (min != null) {
                r.gte(JsonData.of(min));
            }
            if (max != null) {
                r.lte(JsonData.of(max));
            }
            return r;
        }));
    }

    private static List<SortOptions> sorts(String primary) {
        List<SortOptions> sorts = new ArrayList<>();
        switch (primary) {
            case RELEVANCE -> {
                sorts.add(score());
                sorts.add(field("created_at", SortOrder.Desc));
            }
            case "price" -> {
                sorts.add(SortOptions.of(s -> s.field(f -> f.field("current_highest_bid").order(SortOrder.Desc).missing(FieldValue.of("_last")))));
                sorts.add(field("starting_price", SortOrder.Desc));
                sorts.add(score());
            }
            case "like" -> {
                sorts.add(field("like_count", SortOrder.Desc));
                sorts.add(score());
            }
            default -> {
                sorts.add(field("created_at", SortOrder.Desc));
                sorts.add(score());
            }
        }
        sorts.add(field("music_id", SortOrder.Asc));
        return sorts;
    }

    private static SortOptions score() {
        return SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc)));
    }

    private static SortOptions field(String name, SortOrder order) {
        return SortOptions.of(s -> s.field(f -> f.field(name).order(order)));
    }

    private static String escapeWildcard(String text) {
        return text.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }
}
