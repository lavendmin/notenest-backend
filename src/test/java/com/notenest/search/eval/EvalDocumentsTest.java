package com.notenest.search.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalDocumentsTest {

    @Test
    @DisplayName("곡 UUID·등록 시각이 파이썬 시드 생성기(nb5_corpus.py)와 같다 — 스파이크와 같은 tie-break 조건")
    void matchesPythonGenerator() {
        // 기대값: python -c "import nb5_corpus as c; c.music_uuid('A01'); c.build_songs(57)" 결과
        assertThat(EvalDocuments.musicUuid("A01").toString()).isEqualTo("ec4bfd4e-f920-559e-9294-5847d54a9dac");
        Map<String, LocalDateTime> created = EvalDocuments.createdAt(EvalData.corpus());
        assertThat(created.get("A01")).isEqualTo(LocalDateTime.of(2026, 9, 5, 7, 9, 10));
        assertThat(created.get("W01")).isEqualTo(LocalDateTime.of(2026, 9, 2, 8, 25, 10));
    }
}
