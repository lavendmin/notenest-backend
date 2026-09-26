package com.notenest.search.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 캡처한 검색 결과(scripts/nb5/nb5_capture.py 의 phase*-results-*.json)를 qrels 로 채점해 마크다운으로 남긴다.
 *
 * 실행: ./gradlew nb5Eval -Prun=docs/nb5/raw/phase1-like-results-eval-only.json -Plabel=phase1-like-eval-only
 * 출력: docs/nb5/raw/{label}-quality.md
 */
public final class SearchEvalCli {

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args[0].isBlank()) {
            throw new IllegalArgumentException("사용법: SearchEvalCli <run.json> <label>");
        }
        Path run = Path.of(args[0]);
        String label = args[1];

        Map<String, List<String>> runs = readRun(run);
        Set<String> judged = EvalData.corpus().stream().map(EvalData.Song::id).collect(Collectors.toSet());
        SearchEvaluation.Report report = SearchEvaluation.evaluate(EvalData.queries(), EvalData.qrels(), runs, judged);

        String markdown = report.toMarkdown(label + " (run: " + run.toString().replace('\\', '/') + ")");
        Path out = Path.of("docs", "nb5", "raw", label + "-quality.md");
        Files.writeString(out, markdown, StandardCharsets.UTF_8);
        System.out.println(markdown);
        System.out.println("written: " + out);
    }

    static Map<String, List<String>> readRun(Path path) throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(path, StandardCharsets.UTF_8));
        Map<String, List<String>> runs = new LinkedHashMap<>();
        for (JsonNode q : root) {
            List<String> ids = new ArrayList<>();
            q.path("top").forEach(t -> ids.add(t.get("id").asText()));
            runs.put(q.get("qid").asText(), ids);
        }
        return runs;
    }
}
