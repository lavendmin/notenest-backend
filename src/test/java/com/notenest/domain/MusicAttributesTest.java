package com.notenest.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [NB5] BPM·musicalKey 입력 계약 — 정규화·거부 규칙.
 */
class MusicAttributesTest {

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource(delimiter = '|', value = {
            // 단조 — m·min·minor, 대소문자 무시(단어), 공백 허용
            "Am|A_MINOR", "am|A_MINOR", "A minor|A_MINOR", "a min|A_MINOR", "Amin|A_MINOR", "A MINOR|A_MINOR", "  Am  |A_MINOR",
            // 장조 — 표기 없음(대문자 음이름)·maj·major
            "A|A_MAJOR", "A major|A_MAJOR", "Amaj|A_MAJOR", "C|C_MAJOR", "c major|C_MAJOR",
            // 변화표 — 기호·유니코드·단어, 이명동음은 한 값으로
            "F#m|F_SHARP_MINOR", "F# minor|F_SHARP_MINOR", "Gbm|F_SHARP_MINOR", "F♯m|F_SHARP_MINOR", "F sharp minor|F_SHARP_MINOR",
            "Bb|B_FLAT_MAJOR", "A#|B_FLAT_MAJOR", "B♭ major|B_FLAT_MAJOR", "B flat major|B_FLAT_MAJOR",
            "Eb|E_FLAT_MAJOR", "Ebm|E_FLAT_MINOR", "D#m|E_FLAT_MINOR", "C#m|C_SHARP_MINOR", "Dbm|C_SHARP_MINOR",
            "Db|D_FLAT_MAJOR", "C#|D_FLAT_MAJOR", "Abm|G_SHARP_MINOR", "Cb|B_MAJOR", "E#|F_MAJOR",
            // enum 이름 그대로
            "A_MINOR|A_MINOR", "F_SHARP_MAJOR|F_SHARP_MAJOR"
    })
    void parse_normalizesClearEnglishNotation(String raw, MusicalKey expected) {
        assertThat(MusicalKey.parse(raw)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" 거부")
    @ValueSource(strings = {
            "AM", "F#M",            // 대문자 M 단독 — 장조인지 Am 의 대문자인지 모호
            "a", "bb", "f#",        // 소문자 음이름 단독 — 독일식 단조 표기와 충돌
            "라단조", "가장조", "다",  // 한글 음이름 — P0 제외
            "H", "A-", "Am7", "A dorian", "Key: Am", "a_minor", "#", "b", "8A"
    })
    void parse_rejectsAmbiguousOrUnsupported(String raw) {
        assertThatThrownBy(() -> MusicalKey.parse(raw)).isInstanceOf(InvalidMusicAttributeException.class);
    }

    @Test
    @DisplayName("null·공백은 값 없음(null)으로 본다 — 선택 입력")
    void parseOrNull_blankIsAbsent() {
        assertThat(MusicalKey.parseOrNull(null)).isNull();
        assertThat(MusicalKey.parseOrNull("   ")).isNull();
    }

    @Test
    @DisplayName("24개 값이 12 음높이 × 장·단조를 빠짐없이 한 번씩 덮고, 표시명이 자기 자신으로 다시 해석된다")
    void enum_coversAll24KeysOnce() {
        assertThat(MusicalKey.values()).hasSize(24);
        Set<String> seen = new HashSet<>();
        Arrays.stream(MusicalKey.values()).forEach(k -> seen.add(k.pitchClass() + (k.isMinor() ? "m" : "M")));
        assertThat(seen).hasSize(24);
        for (MusicalKey key : MusicalKey.values()) {
            assertThat(MusicalKey.parse(key.displayName())).as(key.displayName()).isEqualTo(key);
        }
    }

    @Test
    @DisplayName("BPM 은 40~250 포함 범위, null 은 선택 입력으로 통과")
    void bpm_range() {
        assertThat(Bpm.requireValidOrNull(40, "bpm")).isEqualTo(40);
        assertThat(Bpm.requireValidOrNull(250, "bpm")).isEqualTo(250);
        assertThat(Bpm.requireValidOrNull(null, "bpm")).isNull();
        assertThatThrownBy(() -> Bpm.requireValidOrNull(39, "bpm")).isInstanceOf(InvalidMusicAttributeException.class);
        assertThatThrownBy(() -> Bpm.requireValidOrNull(251, "bpm")).isInstanceOf(InvalidMusicAttributeException.class);
        assertThatThrownBy(() -> Bpm.requireValidOrNull(0, "bpm")).isInstanceOf(InvalidMusicAttributeException.class);
    }
}
