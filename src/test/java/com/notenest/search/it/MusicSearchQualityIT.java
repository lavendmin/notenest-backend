package com.notenest.search.it;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.notenest.dto.MusicSummaryDTO;
import com.notenest.repository.MusicListCondition;
import com.notenest.search.ElasticsearchMusicSearchAdapter;
import com.notenest.search.ElasticsearchMusicSearchIndex;
import com.notenest.search.MusicSearchQueryFactory;
import com.notenest.search.eval.EvalData;
import com.notenest.search.eval.EvalDocuments;
import com.notenest.search.eval.SearchEvaluation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * [NB5] 채택안(ES 8.11.1 + Nori)의 품질 — 실제 어댑터·매핑·분석기로 평가 코퍼스 57곡에 25개 질의를 돌려 qrels v1 로 채점한다.
 *
 * 기준 1(ACTION_PLAN 완료 조건): 전체·유형별 nDCG@5 가 Phase 1 LIKE 기준선보다 높고, 곡명 MRR@5 가 퇴행하지 않는다.
 * 기준 2(구현 충실도): ADR-001 에서 승인한 Phase 2 스파이크 수치를 그대로 재현한다 — 본 구현이 승인한 설정에서 벗어나지 않았는지 확인한다.
 * 결과 표는 build/nb5-it/phase3-es-adapter-eval-only-quality.md 에 남긴다.
 */
@Tag("es-integration")
class MusicSearchQualityIT {

    private static final String INDEX = "nb5-quality-it";
    private static ElasticsearchMusicSearchAdapter adapter;

    @BeforeAll
    static void indexEvalCorpus() {
        ElasticsearchClient client = ElasticsearchTestContainer.client();
        ElasticsearchMusicSearchIndex index = new ElasticsearchMusicSearchIndex(client, INDEX);
        index.recreate();
        index.upsertAll(EvalDocuments.documents(EvalData.corpus()));
        index.refresh();
        adapter = new ElasticsearchMusicSearchAdapter(client, new MusicSearchQueryFactory(), INDEX);
    }

    @Test
    @DisplayName("nDCG@5·MRR@5 가 LIKE 기준선보다 높고, 승인한 Phase 2 스파이크 수치를 재현한다")
    void qualityAgainstBaselineAndSpike() throws Exception {
        List<EvalData.Song> corpus = EvalData.corpus();
        Map<UUID, String> idByUuid = corpus.stream().collect(Collectors.toMap(s -> EvalDocuments.musicUuid(s.id()), EvalData.Song::id));

        Map<String, List<String>> runs = new LinkedHashMap<>();
        for (EvalData.Query q : EvalData.queries()) {
            Map<String, String> p = q.params();
            MusicListCondition condition = MusicListCondition.of(p.get("majorGenre"), null, longOrNull(p.get("minPrice")),
                    longOrNull(p.get("maxPrice")), intOrNull(p.get("bpmMin")), intOrNull(p.get("bpmMax")),
                    p.get("musicalKey"), q.searchTerm());
            List<MusicSummaryDTO> top = adapter.search(condition, null, PageRequest.of(0, 10)).getContent();
            runs.put(q.qid(), top.stream().map(d -> idByUuid.get(d.getMusicUuid())).toList());
        }
        Set<String> judged = corpus.stream().map(EvalData.Song::id).collect(Collectors.toSet());
        SearchEvaluation.Report report = SearchEvaluation.evaluate(EvalData.queries(), EvalData.qrels(), runs, judged);

        Path out = Path.of("build", "nb5-it", "phase3-es-adapter-eval-only-quality.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toMarkdown("phase3-es-adapter-eval-only (실제 어댑터, Testcontainers ES 8.11.1+Nori)"),
                StandardCharsets.UTF_8);

        // 기준 1 — Phase 1 LIKE 기준선(docs/nb5/raw/phase1-like-eval-only-quality.md)
        assertThat(report.overall().judged()).isEqualTo(1.0);
        assertThat(report.overall().ndcg()).isGreaterThan(0.5084);
        assertThat(report.ofType("T1_TITLE").ndcg()).isGreaterThan(0.5472);
        assertThat(report.ofType("T2_CONDITION").ndcg()).isGreaterThan(0.3726);
        assertThat(report.ofType("T3_MOOD").ndcg()).isGreaterThan(0.5079);
        assertThat(report.ofType("T4_SELLER").ndcg()).isGreaterThan(0.6254);
        assertThat(report.ofType("T1_TITLE").mrr()).isGreaterThanOrEqualTo(0.3889);

        // 기준 2 — Phase 2 스파이크(docs/nb5/raw/phase2-es-nori-eval-only-quality.md)
        assertThat(report.overall().ndcg()).isCloseTo(0.8860, within(0.00005));
        assertThat(report.ofType("T1_TITLE").mrr()).isCloseTo(1.0, within(0.00005));
        assertThat(report.ofType("T1_TITLE").ndcg()).isCloseTo(0.9357, within(0.00005));
        assertThat(report.ofType("T2_CONDITION").ndcg()).isCloseTo(0.7456, within(0.00005));
        assertThat(report.ofType("T3_MOOD").ndcg()).isCloseTo(0.8757, within(0.00005));
        assertThat(report.ofType("T4_SELLER").ndcg()).isCloseTo(1.0, within(0.00005));
    }

    private static Long longOrNull(String v) {
        return v == null ? null : Long.valueOf(v);
    }

    private static Integer intOrNull(String v) {
        return v == null ? null : Integer.valueOf(v);
    }
}
