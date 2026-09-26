package com.notenest.search.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SearchEvaluationTest {

    private static final List<EvalData.Query> QUERIES = List.of(
            new EvalData.Query("q1", "T1_TITLE", "x", Map.of()),
            new EvalData.Query("q2", "T1_TITLE", "y", Map.of()),
            new EvalData.Query("q3", "T4_SELLER", "z", Map.of()));
    private static final Qrels QRELS = new Qrels(Map.of(
            "q1", Map.of("a", 3),
            "q2", Map.of("b", 3),
            "q3", Map.of("c", 3)));

    @Test
    @DisplayName("전체·유형별 평균을 내고, MRR 은 T1 질의만으로 계산한다")
    void aggregatesByType() {
        Map<String, List<String>> runs = Map.of(
                "q1", List.of("a"),              // nDCG 1, RR 1
                "q2", List.of("n", "b"),         // nDCG 1/log2(3), RR 0.5
                "q3", List.of("n", "n2", "c"));  // nDCG 1/2, RR 없음(T4)
        SearchEvaluation.Report report = SearchEvaluation.evaluate(QUERIES, QRELS, runs, Set.of("a", "b", "c"));

        double q2 = 1 / (Math.log(3) / Math.log(2));
        assertThat(report.overall().ndcg()).isCloseTo((1 + q2 + 0.5) / 3, within(1e-12));
        assertThat(report.overall().mrr()).isCloseTo(0.75, within(1e-12));
        assertThat(report.ofType("T1_TITLE").ndcg()).isCloseTo((1 + q2) / 2, within(1e-12));
        assertThat(report.ofType("T4_SELLER").mrr()).isNull();
        assertThat(report.ofType("T4_SELLER").judged()).isCloseTo(1.0 / 3, within(1e-12));
        assertThat(report.summaries()).extracting(SearchEvaluation.Summary::scope)
                .containsExactly("ALL", "T1_TITLE", "T4_SELLER");
        assertThat(report.toMarkdown("t")).contains("| ALL | 3 |");
    }

    @Test
    @DisplayName("run 에 질의가 빠지면 조용히 넘어가지 않고 실패한다")
    void missingRunFails() {
        assertThatThrownBy(() -> SearchEvaluation.evaluate(QUERIES, QRELS, Map.of("q1", List.of()), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
