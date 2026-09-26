package com.notenest.search.eval;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 질의별 곡 relevance grade(0~3). 적히지 않은 곡은 0등급이다.
 */
public final class Qrels {

    private final Map<String, Map<String, Integer>> gradesByQuery;

    public Qrels(Map<String, Map<String, Integer>> gradesByQuery) {
        this.gradesByQuery = Map.copyOf(gradesByQuery);
    }

    public Map<String, Integer> gradesOf(String qid) {
        Map<String, Integer> grades = gradesByQuery.get(qid);
        if (grades == null) {
            throw new IllegalArgumentException("qrels 에 없는 질의: " + qid);
        }
        return Collections.unmodifiableMap(grades);
    }

    public Set<String> queryIds() {
        return gradesByQuery.keySet();
    }
}
