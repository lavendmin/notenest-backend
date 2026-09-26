package com.notenest.search;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonpUtils;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notenest.repository.MusicListCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [NB5] 검색 질의 조립 — Elasticsearch 없이 요청 JSON 으로 검증한다. 가중치·정확 일치·장르 별칭은 Phase 2 사전 고정값
 * (scripts/nb5/phase2/nb5_query.py, 커밋 03546be)과 같아야 한다.
 */
class MusicSearchQueryFactoryTest {

    private final MusicSearchQueryFactory factory = new MusicSearchQueryFactory();
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode build(MusicListCondition condition, String sortBy, int page, int size) throws Exception {
        SearchRequest request = factory.build("idx", condition, sortBy, PageRequest.of(page, size));
        return json.readTree(JsonpUtils.toJsonString(request, new JacksonJsonpMapper()));
    }

    private static MusicListCondition term(String searchTerm) {
        return MusicListCondition.of(null, null, null, null, null, null, null, searchTerm);
    }

    private static List<String> sortKeys(JsonNode body) {
        List<String> keys = new ArrayList<>();
        body.path("sort").forEach(s -> {
            String key = s.fieldNames().next();
            JsonNode v = s.get(key);
            keys.add(key + ":" + (v.isTextual() ? v.asText() : v.path("order").asText()));
        });
        return keys;
    }

    @Test
    @DisplayName("관련도: 가중치 multi_match + 정확 제목·정확 판매자 +100(동일) — Phase 2 사전 고정값과 같다")
    void relevanceClausesMatchPreRegisteredSpike() throws Exception {
        JsonNode bool = build(term("  Blue Hour "), null, 0, 10).path("query").path("bool");

        JsonNode multi = bool.path("should").get(0).path("multi_match");
        assertThat(multi.path("query").asText()).isEqualTo("Blue Hour");
        assertThat(multi.path("type").asText()).isEqualTo("best_fields");
        assertThat(multi.path("tie_breaker").asDouble()).isEqualTo(0.3);
        assertThat(multi.path("fields")).extracting(JsonNode::asText)
                .containsExactly("title^3.0", "seller^3.0", "subtitle^2.0", "hashtag^1.5", "genre^1.0", "details^1.0");

        JsonNode exactTitle = bool.path("should").get(1).path("constant_score");
        JsonNode exactSeller = bool.path("should").get(2).path("constant_score");
        assertThat(exactTitle.path("filter").path("term").path("title.compact").path("value").asText()).isEqualTo("bluehour");
        assertThat(exactSeller.path("filter").path("term").path("seller.compact").path("value").asText()).isEqualTo("bluehour");
        assertThat(exactTitle.path("boost").asDouble()).isEqualTo(100.0).isEqualTo(exactSeller.path("boost").asDouble());
        assertThat(bool.path("should")).hasSize(3); // 장르 별칭 없음
        assertThat(bool.path("minimum_should_match").asText()).isEqualTo("1");
    }

    @Test
    @DisplayName("한글 장르명 어절은 저장 코드에 +2 (결정 2) — 어절이 정확히 같을 때만")
    void genreAliasBoost() throws Exception {
        JsonNode should = build(term("피아노 발라드"), null, 0, 10).path("query").path("bool").path("should");
        assertThat(should).hasSize(4);
        JsonNode alias = should.get(3).path("constant_score");
        assertThat(alias.path("filter").path("term").path("genre").path("value").asText()).isEqualTo("balad");
        assertThat(alias.path("boost").asDouble()).isEqualTo(2.0);

        assertThat(MusicSearchQueryFactory.genreCodes("힙합 트로트 발라드풍")).containsExactly("hiphop", "trot");
        assertThat(MusicSearchQueryFactory.genreCodes("케이팝 팝")).containsExactly("pop");
    }

    @Test
    @DisplayName("필터: 진행 중 + 장르 + 해시태그(태그별 부분 일치, 와일드카드 문자 이스케이프) + 가격(현재가 기준) + BPM + 키")
    void filtersMirrorListContract() throws Exception {
        MusicListCondition c = MusicListCondition.of("POP", "#seed, a*b?", 1000L, 5000L, 85, 95, "Am", "x");
        JsonNode filter = build(c, null, 0, 10).path("query").path("bool").path("filter");

        assertThat(filter.get(0).path("term").path("status").path("value").asInt()).isZero();
        assertThat(filter.get(1).path("term").path("genre").path("value").asText()).isEqualTo("POP");
        assertThat(filter.get(2).path("wildcard").path("hashtag.raw").path("value").asText()).isEqualTo("*#seed*");
        assertThat(filter.get(2).path("wildcard").path("hashtag.raw").path("case_insensitive").asBoolean()).isTrue();
        assertThat(filter.get(3).path("wildcard").path("hashtag.raw").path("value").asText()).isEqualTo("*a\\*b\\?*");
        assertThat(filter.get(4).path("range").path("price").path("gte").asLong()).isEqualTo(1000);
        assertThat(filter.get(4).path("range").path("price").path("lte").asLong()).isEqualTo(5000);
        assertThat(filter.get(5).path("range").path("bpm").path("gte").asInt()).isEqualTo(85);
        assertThat(filter.get(5).path("range").path("bpm").path("lte").asInt()).isEqualTo(95);
        assertThat(filter.get(6).path("term").path("musical_key").path("value").asText()).isEqualTo("A_MINOR");
        assertThat(filter).hasSize(7);
    }

    @Test
    @DisplayName("필터 없는 검색은 진행 중 조건만 — 한쪽 경계만 있는 범위도 그 경계만 건다")
    void onlyGivenFilters() throws Exception {
        assertThat(build(term("x"), null, 0, 10).path("query").path("bool").path("filter")).hasSize(1);
        JsonNode filter = build(MusicListCondition.of(null, null, null, 3000L, 120, null, null, "x"), null, 0, 10)
                .path("query").path("bool").path("filter");
        assertThat(filter.get(1).path("range").path("price").has("gte")).isFalse();
        assertThat(filter.get(2).path("range").path("bpm").has("lte")).isFalse();
    }

    @Test
    @DisplayName("정렬: 생략·relevance = 관련도 → 최신 → id, 명시 정렬 = 요청 키 → 관련도 → id, 알 수 없는 값 = 최신순")
    void sortsHaveStableTieBreak() throws Exception {
        assertThat(sortKeys(build(term("x"), null, 0, 10))).containsExactly("_score:desc", "created_at:desc", "music_id:asc");
        assertThat(sortKeys(build(term("x"), "relevance", 0, 10))).containsExactly("_score:desc", "created_at:desc", "music_id:asc");
        assertThat(sortKeys(build(term("x"), "latest", 0, 10))).containsExactly("created_at:desc", "_score:desc", "music_id:asc");
        assertThat(sortKeys(build(term("x"), "price", 0, 10)))
                .containsExactly("current_highest_bid:desc", "starting_price:desc", "_score:desc", "music_id:asc");
        assertThat(sortKeys(build(term("x"), "like", 0, 10))).containsExactly("like_count:desc", "_score:desc", "music_id:asc");
        assertThat(sortKeys(build(term("x"), "whatever", 0, 10))).containsExactly("created_at:desc", "_score:desc", "music_id:asc");

        JsonNode price = build(term("x"), "price", 0, 10);
        assertThat(price.path("sort").get(0).path("current_highest_bid").path("missing").asText()).isEqualTo("_last");
        assertThat(price.path("track_scores").asBoolean()).as("명시 정렬도 _score 를 계산해야 2차 정렬이 된다").isTrue();
        assertThat(build(term("x"), null, 0, 10).path("track_scores").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("페이지: from = page × size, 전체 건수 추적, 10,000번째를 넘는 페이지는 400")
    void paginationAndResultWindow() throws Exception {
        JsonNode body = build(term("x"), null, 3, 20);
        assertThat(body.path("from").asInt()).isEqualTo(60);
        assertThat(body.path("size").asInt()).isEqualTo(20);
        assertThat(body.path("track_total_hits").asBoolean()).isTrue();

        build(term("x"), null, 999, 10); // from 9990 + size 10 = 10000 은 허용
        assertThatThrownBy(() -> build(term("x"), null, 1000, 10)).isInstanceOf(InvalidSearchRequestException.class);
    }

    @Test
    @DisplayName("Page 메타데이터: 관련도순은 _score·createdAt, 명시 정렬은 비검색 경로와 같은 표현")
    void sortMeta() {
        assertThat(factory.sortMeta(null).toString()).isEqualTo("_score: DESC,createdAt: DESC");
        assertThat(factory.sortMeta("price").toString()).isEqualTo("currentHighestBid: DESC, NULLS_LAST,startingPrice: DESC");
        assertThat(factory.sortMeta("like").toString()).isEqualTo("likeCount: DESC");
    }
}
