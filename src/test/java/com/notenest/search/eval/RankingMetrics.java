package com.notenest.search.eval;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 순위 품질 지표. 정의는 docs/nb5/eval/qrels-guidelines.md 의 "지표 정의"와 같다.
 *
 * - nDCG@k: 이득 2^grade − 1, 할인 log2(순위 + 1), 이상적 DCG 는 qrels 등급 내림차순 상위 k
 * - RR@k: 그 질의의 최고 등급 곡이 상위 k 에 처음 나온 순위의 역수(없으면 0) — 평균하면 MRR@k
 * - Top-k 적중률: 최고 등급 곡 중 상위 k 에 든 비율, 분모 min(k, 최고 등급 곡 수)
 * - judged@k: 상위 k 중 판정된(코퍼스에 있는) 곡의 비율
 */
public final class RankingMetrics {

    private RankingMetrics() {
    }

    public static double ndcgAtK(List<String> ranking, Map<String, Integer> grades, int k) {
        double ideal = idealDcg(grades, k);
        if (ideal == 0.0) {
            throw new IllegalArgumentException("관련 곡(등급 > 0)이 없는 질의는 nDCG 를 정의할 수 없다");
        }
        return dcg(ranking, grades, k) / ideal;
    }

    public static double reciprocalRankAtK(List<String> ranking, Map<String, Integer> grades, int k) {
        int maxGrade = maxGrade(grades);
        for (int i = 0; i < Math.min(k, ranking.size()); i++) {
            if (grade(grades, ranking.get(i)) == maxGrade) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    public static double topKHitRate(List<String> ranking, Map<String, Integer> grades, int k) {
        int maxGrade = maxGrade(grades);
        long best = grades.values().stream().filter(g -> g == maxGrade).count();
        long hit = ranking.stream().limit(k).filter(id -> grade(grades, id) == maxGrade).count();
        return (double) hit / Math.min(k, best);
    }

    public static double judgedAtK(List<String> ranking, Collection<String> judgedIds, int k) {
        List<String> top = ranking.subList(0, Math.min(k, ranking.size()));
        if (top.isEmpty()) {
            return 1.0; // 판정되지 않은 곡이 하나도 없다
        }
        return (double) top.stream().filter(judgedIds::contains).count() / top.size();
    }

    static double dcg(List<String> ranking, Map<String, Integer> grades, int k) {
        double sum = 0.0;
        for (int i = 0; i < Math.min(k, ranking.size()); i++) {
            sum += gain(grade(grades, ranking.get(i))) / log2(i + 2);
        }
        return sum;
    }

    static double idealDcg(Map<String, Integer> grades, int k) {
        List<Integer> sorted = grades.values().stream().sorted((a, b) -> b - a).limit(k).toList();
        double sum = 0.0;
        for (int i = 0; i < sorted.size(); i++) {
            sum += gain(sorted.get(i)) / log2(i + 2);
        }
        return sum;
    }

    private static int maxGrade(Map<String, Integer> grades) {
        int max = grades.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (max == 0) {
            throw new IllegalArgumentException("관련 곡(등급 > 0)이 없는 질의");
        }
        return max;
    }

    private static int grade(Map<String, Integer> grades, String id) {
        return grades.getOrDefault(id, 0);
    }

    private static double gain(int grade) {
        return Math.pow(2, grade) - 1;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
