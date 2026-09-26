package com.notenest.search.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NB5 평가 자료(코퍼스·질의셋·qrels) 로더. 파일은 docs/nb5/eval 에 있고 테스트 작업 디렉터리(프로젝트 루트) 기준으로 읽는다.
 */
public final class EvalData {

    public static final Path EVAL_DIR = Path.of("docs", "nb5", "eval");
    public static final Path CORPUS = EVAL_DIR.resolve("corpus-v0.jsonl");
    public static final Path QUERIES = EVAL_DIR.resolve("queries-v0.jsonl");
    public static final Path QRELS = EVAL_DIR.resolve("qrels-v1.jsonl");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalData() {
    }

    /** 평가 코퍼스의 곡. bpm·key 는 설계상 정답 속성이다. */
    public record Song(String id, String title, String subtitle, String genre, String hashtag, String details,
                       String seller, long start, Long bid, int likes, int status, Integer bpm, String key) {

        /** 가격 필터 기준 — 현재가(최고 입찰가), 없으면 시작가. */
        public long effectivePrice() {
            return bid != null ? bid : start;
        }

        public boolean ongoing() {
            return status == 0;
        }
    }

    /** 질의. type 은 T1_TITLE·T2_CONDITION·T3_MOOD·T4_SELLER. */
    public record Query(String qid, String type, String searchTerm, Map<String, String> params) {
    }

    public static List<Song> corpus() {
        List<Song> songs = new ArrayList<>();
        for (JsonNode n : readJsonLines(CORPUS)) {
            songs.add(new Song(n.get("id").asText(), n.get("title").asText(), text(n, "subtitle"), n.get("genre").asText(),
                    text(n, "hashtag"), text(n, "details"), n.get("seller").asText(), n.get("start").asLong(),
                    n.hasNonNull("bid") ? n.get("bid").asLong() : null, n.get("likes").asInt(), n.get("status").asInt(),
                    n.hasNonNull("bpm") ? n.get("bpm").asInt() : null, text(n, "key")));
        }
        return songs;
    }

    public static List<Query> queries() {
        List<Query> queries = new ArrayList<>();
        for (JsonNode n : readJsonLines(QUERIES)) {
            Map<String, String> params = new LinkedHashMap<>();
            n.path("params").fields().forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));
            queries.add(new Query(n.get("qid").asText(), n.get("type").asText(), n.get("searchTerm").asText(), params));
        }
        return queries;
    }

    public static Qrels qrels() {
        Map<String, Map<String, Integer>> byQuery = new LinkedHashMap<>();
        for (JsonNode n : readJsonLines(QRELS)) {
            Map<String, Integer> grades = new LinkedHashMap<>();
            n.get("grades").fields().forEachRemaining(e -> grades.put(e.getKey(), e.getValue().asInt()));
            if (byQuery.put(n.get("qid").asText(), grades) != null) {
                throw new IllegalStateException("qrels 에 중복 qid: " + n.get("qid").asText());
            }
        }
        return new Qrels(byQuery);
    }

    static List<JsonNode> readJsonLines(Path path) {
        try {
            List<JsonNode> nodes = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    nodes.add(MAPPER.readTree(line));
                }
            }
            return nodes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }
}
