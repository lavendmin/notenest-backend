package com.notenest.search.eval;

import com.notenest.domain.Bpm;
import com.notenest.domain.MusicalKey;
import com.notenest.search.eval.EvalData.Query;
import com.notenest.search.eval.EvalData.Song;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * qrels-v1 이 판정 규칙(docs/nb5/eval/qrels-guidelines.md)과 코퍼스에 맞는지 검사한다.
 *
 * 규칙 중 코퍼스 속성만으로 기계적으로 계산되는 부분(정확 제목, 정확 판매자, 구조화 조건, 장르)은 여기서 다시 계산해
 * 파일과 대조한다. 부제·설명의 의미 판정(T3 등)은 사람이 정한 값이므로 형식·범위만 검사한다.
 */
class QrelsIntegrityTest {

    private static List<Song> corpus;
    private static Map<String, Song> songs;
    private static List<Query> queries;
    private static Qrels qrels;

    @BeforeAll
    static void load() {
        corpus = EvalData.corpus();
        songs = corpus.stream().collect(Collectors.toMap(Song::id, s -> s));
        queries = EvalData.queries();
        qrels = EvalData.qrels();
    }

    @Test
    @DisplayName("질의셋과 qrels 의 질의가 1:1 로 대응하고, 네 유형을 모두 포함한다")
    void everyQueryHasQrels() {
        assertThat(qrels.queryIds()).containsExactlyInAnyOrderElementsOf(queries.stream().map(Query::qid).toList());
        assertThat(queries.stream().map(Query::type).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(SearchEvaluation.TYPES);
    }

    @Test
    @DisplayName("판정된 곡은 모두 코퍼스에 있고, 등급은 1~3(0 은 적지 않는다), 질의마다 최고 등급이 2 이상")
    void gradesAreWellFormed() {
        for (Query q : queries) {
            Map<String, Integer> grades = qrels.gradesOf(q.qid());
            assertThat(songs.keySet()).as(q.qid() + " 코퍼스 id").containsAll(grades.keySet());
            assertThat(grades.values()).as(q.qid() + " 등급 범위").allMatch(g -> g >= 1 && g <= 3);
            assertThat(grades.values().stream().mapToInt(Integer::intValue).max().orElse(0)).as(q.qid() + " 최고 등급")
                    .isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    @DisplayName("종료된 경매 곡(status=1)은 어떤 질의에서도 관련 등급을 받지 않는다")
    void endedSongsAreNeverRelevant() {
        Set<String> ended = corpus.stream().filter(s -> !s.ongoing()).map(Song::id).collect(Collectors.toSet());
        assertThat(ended).isNotEmpty();
        for (Query q : queries) {
            assertThat(qrels.gradesOf(q.qid()).keySet()).as(q.qid()).doesNotContainAnyElementsOf(ended);
        }
    }

    @Test
    @DisplayName("T1: 3등급 = 정규화(대소문자·공백 무시) 제목이 질의와 같은 진행 중 곡")
    void titleQueries_grade3IsExactTitle() {
        for (Query q : ofType("T1_TITLE")) {
            Set<String> exact = ids(s -> norm(s.title()).equals(norm(q.searchTerm())));
            assertThat(withGrade(q, 3)).as(q.qid() + " " + q.searchTerm()).isEqualTo(exact);
        }
    }

    @Test
    @DisplayName("T4: 3등급 = 정확 판매자의 곡 ∪ 정확 제목의 곡(결정 5 — 충돌 시 둘 다 3)")
    void sellerQueries_grade3IsExactSellerOrExactTitle() {
        for (Query q : ofType("T4_SELLER")) {
            String term = norm(q.searchTerm());
            Set<String> expected = ids(s -> norm(s.seller()).equals(term) || norm(s.title()).equals(term));
            assertThat(withGrade(q, 3)).as(q.qid() + " " + q.searchTerm()).isEqualTo(expected);
        }
        // Q32 윤슬은 정확 제목(W01)과 정확 판매자가 실제로 충돌하는 사례여야 한다
        assertThat(withGrade(query("Q32"), 3)).contains("W01").hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("T2 하드 제약: 조건을 어긴 곡은 판정되지 않고, 순수 구조화 질의의 3등급 집합은 조건 계산과 같다")
    void conditionQueries_matchStructuredConstraints() {
        Predicate<Song> hiphopUnder200k = s -> s.genre().equals("hiphop") && s.effectivePrice() <= 200_000;
        // Q10: 조건 충족 곡만 판정(1 또는 3), 충족 곡은 모두 판정
        assertThat(qrels.gradesOf("Q10").keySet()).isEqualTo(ids(hiphopUnder200k));
        // Q11: 한글 장르명 → hiphop (결정 2)
        assertThat(withGrade(query("Q11"), 3)).isEqualTo(ids(hiphopUnder200k));
        // Q12: BPM 90 = 3, 85~95 = 2
        assertThat(withGrade(query("Q12"), 3)).isEqualTo(ids(s -> Integer.valueOf(90).equals(s.bpm())));
        assertThat(qrels.gradesOf("Q12").keySet()).isEqualTo(ids(s -> s.bpm() != null && s.bpm() >= 85 && s.bpm() <= 95));
        // Q13: hiphop + BPM 85~95 + A minor
        Predicate<Song> aMinorHiphop = s -> s.genre().equals("hiphop") && key(s) == MusicalKey.A_MINOR;
        assertThat(withGrade(query("Q13"), 3)).isEqualTo(ids(aMinorHiphop.and(s -> s.bpm() >= 85 && s.bpm() <= 95)));
        assertThat(qrels.gradesOf("Q13")).hasSameSizeAs(withGrade(query("Q13"), 3));
        // Q14: BPM 120 / Q15: A minor 힙합
        assertThat(withGrade(query("Q14"), 3)).isEqualTo(ids(s -> Integer.valueOf(120).equals(s.bpm())));
        assertThat(withGrade(query("Q15"), 3)).isEqualTo(ids(aMinorHiphop));
    }

    @Test
    @DisplayName("Q25 발라드: 3등급 = 장르 balad 인 진행 중 곡 전부(한글 장르명 매핑)")
    void genreQuery_isGenreSet() {
        assertThat(qrels.gradesOf("Q25").keySet()).isEqualTo(ids(s -> s.genre().equals("balad")));
        assertThat(withGrade(query("Q25"), 3)).hasSize(qrels.gradesOf("Q25").size());
    }

    @Test
    @DisplayName("코퍼스의 설계 속성 bpm·key 가 NB5 계약(40~250 정수, 24개 키)으로 해석된다")
    void corpusAttributesFollowContract() {
        for (Song s : corpus) {
            assertThat(s.bpm()).as(s.id() + " bpm").isNotNull().isBetween(Bpm.MIN, Bpm.MAX);
            assertThat(key(s)).as(s.id() + " key " + s.key()).isNotNull();
        }
    }

    private static MusicalKey key(Song s) {
        return MusicalKey.parseOrNull(s.key());
    }

    private static List<Query> ofType(String type) {
        return queries.stream().filter(q -> q.type().equals(type)).toList();
    }

    private static Query query(String qid) {
        return queries.stream().filter(q -> q.qid().equals(qid)).findFirst().orElseThrow();
    }

    private static Set<String> withGrade(Query q, int grade) {
        return qrels.gradesOf(q.qid()).entrySet().stream()
                .filter(e -> e.getValue() == grade).map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    /** 진행 중 곡 가운데 조건을 만족하는 id. */
    private static Set<String> ids(Predicate<Song> p) {
        return corpus.stream().filter(Song::ongoing).filter(p).map(Song::id).collect(Collectors.toSet());
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
