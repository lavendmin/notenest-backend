package com.notenest.search.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 지표 계산기 검증. 기대값은 정의식(이득 2^g−1, 할인 log2(i+1))을 손으로 풀어 적었다.
 */
class RankingMetricsTest {

    private static final Map<String, Integer> GRADES = Map.of("a", 3, "c", 2, "d", 1);

    @Test
    @DisplayName("nDCG@5 — 무관 곡이 끼면 DCG 는 8.5, 이상적 DCG 는 7 + 3/log2(3) + 1/2 = 9.3928 → 0.9049")
    void ndcg_handComputed() {
        // DCG = 7/log2(2) + 0/log2(3) + 3/log2(4) = 7 + 0 + 1.5
        assertThat(RankingMetrics.dcg(List.of("a", "b", "c"), GRADES, 5)).isCloseTo(8.5, within(1e-9));
        assertThat(RankingMetrics.idealDcg(GRADES, 5)).isCloseTo(7 + 3 / (Math.log(3) / Math.log(2)) + 0.5, within(1e-9));
        assertThat(RankingMetrics.ndcgAtK(List.of("a", "b", "c"), GRADES, 5)).isCloseTo(0.9049495058, within(1e-9));
    }

    @Test
    @DisplayName("nDCG@5 — 이상적 순서면 1, 관련 곡이 없으면 0")
    void ndcg_boundaries() {
        assertThat(RankingMetrics.ndcgAtK(List.of("a", "c", "d"), GRADES, 5)).isCloseTo(1.0, within(1e-12));
        assertThat(RankingMetrics.ndcgAtK(List.of("x", "y"), GRADES, 5)).isZero();
        assertThat(RankingMetrics.ndcgAtK(List.of(), GRADES, 5)).isZero();
    }

    @Test
    @DisplayName("nDCG@5 — 정답이 5위면 7/log2(6) ÷ 7 = 0.3869, 6위 이하는 잘린다")
    void ndcg_cutoffAtK() {
        assertThat(RankingMetrics.ndcgAtK(List.of("x", "y", "z", "w", "a"), Map.of("a", 3), 5))
                .isCloseTo(0.3868528072, within(1e-9));
        assertThat(RankingMetrics.ndcgAtK(List.of("x", "y", "z", "w", "v", "a"), Map.of("a", 3), 5)).isZero();
    }

    @Test
    @DisplayName("nDCG — 관련 곡이 하나도 없는 qrels 는 정의되지 않아 예외")
    void ndcg_undefinedWithoutRelevant() {
        assertThatThrownBy(() -> RankingMetrics.ndcgAtK(List.of("a"), Map.of("a", 0), 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RR@5 — 최고 등급 곡의 첫 순위 역수. 낮은 등급 곡이 먼저 나와도 세지 않는다")
    void reciprocalRank_usesMaxGrade() {
        assertThat(RankingMetrics.reciprocalRankAtK(List.of("c", "d", "a"), GRADES, 5)).isCloseTo(1.0 / 3, within(1e-12));
        assertThat(RankingMetrics.reciprocalRankAtK(List.of("a"), GRADES, 5)).isEqualTo(1.0);
        assertThat(RankingMetrics.reciprocalRankAtK(List.of("x", "y", "z", "w", "v", "a"), GRADES, 5)).isZero();
        // 최고 등급이 2인 질의(정확 제목 없음)는 2등급 곡이 정답이다
        assertThat(RankingMetrics.reciprocalRankAtK(List.of("x", "q"), Map.of("p", 2, "q", 2), 5)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("Top-5 적중률 — 분모는 min(5, 최고 등급 곡 수)")
    void topKHitRate() {
        Map<String, Integer> sevenBest = Map.of("s1", 3, "s2", 3, "s3", 3, "s4", 3, "s5", 3, "s6", 3, "s7", 3);
        assertThat(RankingMetrics.topKHitRate(List.of("s1", "x", "s2", "s3", "s4", "s5"), sevenBest, 5)).isEqualTo(4.0 / 5);
        assertThat(RankingMetrics.topKHitRate(List.of("c", "a"), GRADES, 5)).isEqualTo(1.0);
        assertThat(RankingMetrics.topKHitRate(List.of("c", "d"), GRADES, 5)).isZero();
    }

    @Test
    @DisplayName("judged@5 — 상위 5개 중 판정된 곡 비율, 결과가 없으면 1")
    void judged() {
        Set<String> judged = Set.of("a", "c");
        assertThat(RankingMetrics.judgedAtK(List.of("a", "F00001", "c", "F00002"), judged, 5)).isEqualTo(0.5);
        assertThat(RankingMetrics.judgedAtK(List.of(), judged, 5)).isEqualTo(1.0);
    }
}
