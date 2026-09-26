package com.notenest.search.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.ToDoubleFunction;

/**
 * 질의별 순위 결과(run)를 qrels 로 채점해 전체·유형별 평균을 낸다. 검색 후보(LIKE·FULLTEXT·Elasticsearch)와 무관하게
 * "qid → 곡 id 순위" 만 받는다.
 */
public final class SearchEvaluation {

    public static final int K = 5;
    public static final String TITLE_TYPE = "T1_TITLE";
    public static final List<String> TYPES = List.of("T1_TITLE", "T2_CONDITION", "T3_MOOD", "T4_SELLER");

    private SearchEvaluation() {
    }

    public record QueryScore(String qid, String type, List<String> top, double ndcg, Double reciprocalRank,
                             double topKHit, double judged) {
    }

    public record Summary(String scope, int queries, double ndcg, Double mrr, double topKHit, double judged) {
    }

    public record Report(List<QueryScore> scores, List<Summary> summaries) {

        public Summary overall() {
            return summaries.get(0);
        }

        public Summary ofType(String type) {
            return summaries.stream().filter(s -> s.scope().equals(type)).findFirst().orElseThrow();
        }

        public String toMarkdown(String label) {
            StringBuilder sb = new StringBuilder();
            sb.append("# NB5 검색 품질 — ").append(label).append("\n\n");
            sb.append("qrels: docs/nb5/eval/qrels-v1.jsonl / k = ").append(K).append(" / MRR 은 T1 만\n\n");
            sb.append("| 범위 | 질의 수 | nDCG@5 | MRR@5 | Top-5 적중률 | judged@5 |\n|---|---|---|---|---|---|\n");
            for (Summary s : summaries) {
                sb.append(String.format(Locale.ROOT, "| %s | %d | %.4f | %s | %.4f | %.4f |%n", s.scope(), s.queries(),
                        s.ndcg(), s.mrr() == null ? "-" : String.format(Locale.ROOT, "%.4f", s.mrr()), s.topKHit(), s.judged()));
            }
            sb.append("\n| qid | 유형 | nDCG@5 | RR@5 | Top-5 적중률 | 상위 5 |\n|---|---|---|---|---|---|\n");
            for (QueryScore q : scores) {
                sb.append(String.format(Locale.ROOT, "| %s | %s | %.4f | %s | %.4f | %s |%n", q.qid(), q.type(), q.ndcg(),
                        q.reciprocalRank() == null ? "-" : String.format(Locale.ROOT, "%.4f", q.reciprocalRank()),
                        q.topKHit(), String.join(" ", q.top())));
            }
            return sb.toString();
        }
    }

    public static Report evaluate(List<EvalData.Query> queries, Qrels qrels, Map<String, List<String>> runs,
                                  Collection<String> judgedIds) {
        List<QueryScore> scores = new ArrayList<>();
        for (EvalData.Query q : queries) {
            List<String> ranking = runs.get(q.qid());
            if (ranking == null) {
                throw new IllegalArgumentException("run 에 없는 질의: " + q.qid());
            }
            Map<String, Integer> grades = qrels.gradesOf(q.qid());
            scores.add(new QueryScore(q.qid(), q.type(), ranking.stream().limit(K).toList(),
                    RankingMetrics.ndcgAtK(ranking, grades, K),
                    TITLE_TYPE.equals(q.type()) ? RankingMetrics.reciprocalRankAtK(ranking, grades, K) : null,
                    RankingMetrics.topKHitRate(ranking, grades, K),
                    RankingMetrics.judgedAtK(ranking, judgedIds, K)));
        }

        Map<String, List<QueryScore>> scopes = new LinkedHashMap<>();
        scopes.put("ALL", scores);
        for (String type : TYPES) {
            scopes.put(type, scores.stream().filter(s -> s.type().equals(type)).toList());
        }
        List<Summary> summaries = new ArrayList<>();
        scopes.forEach((scope, list) -> {
            if (list.isEmpty()) {
                return;
            }
            List<QueryScore> titled = list.stream().filter(s -> s.reciprocalRank() != null).toList();
            summaries.add(new Summary(scope, list.size(), mean(list, QueryScore::ndcg),
                    titled.isEmpty() ? null : mean(titled, QueryScore::reciprocalRank),
                    mean(list, QueryScore::topKHit), mean(list, QueryScore::judged)));
        });
        return new Report(scores, summaries);
    }

    private static double mean(List<QueryScore> list, ToDoubleFunction<QueryScore> f) {
        OptionalDouble avg = list.stream().mapToDouble(f).average();
        return avg.orElseThrow();
    }
}
