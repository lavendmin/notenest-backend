package com.notenest.domain;

import java.util.Locale;

/**
 * [NB5] 곡의 조성(키) — 12 음높이 × 장·단조 = 24개 값.
 *
 * 이명동음은 한 값으로 합친다(C# = Db). 필터가 "같은 소리"를 찾기 위한 값이기 때문이다. 표시명은 관용 표기를 쓴다.
 * DB·API 에는 enum 이름(A_MINOR 등)을 저장·전달한다.
 *
 * 입력 정규화({@link #parse}) — 명확한 영문 표기만 받는다:
 *  - 음이름: A~G(대소문자 무시) + 선택 변화표 #·b·♯·♭ 또는 단어 sharp·flat
 *  - 조: m·min·minor → 단조, maj·major → 장조, 조 표기가 없으면 장조(대문자 음이름일 때만)
 *  - enum 이름 그대로(A_MINOR)도 받는다
 * 거부:
 *  - 모호한 표기: 대문자 M 단독 접미사(AM — 장조 표기인지 Am 의 대문자 입력인지 알 수 없다),
 *    소문자 음이름 단독(a — 독일식 표기에서는 단조를 뜻한다)
 *  - 한글 음이름(라단조 등) — P0 범위 밖
 *  - 그 밖에 해석할 수 없는 값
 * 관계조(C major ↔ A minor)·캄로트 휠 동치는 다루지 않는다.
 */
public enum MusicalKey {

    C_MAJOR(0, false, "C"),
    D_FLAT_MAJOR(1, false, "Db"),
    D_MAJOR(2, false, "D"),
    E_FLAT_MAJOR(3, false, "Eb"),
    E_MAJOR(4, false, "E"),
    F_MAJOR(5, false, "F"),
    F_SHARP_MAJOR(6, false, "F#"),
    G_MAJOR(7, false, "G"),
    A_FLAT_MAJOR(8, false, "Ab"),
    A_MAJOR(9, false, "A"),
    B_FLAT_MAJOR(10, false, "Bb"),
    B_MAJOR(11, false, "B"),

    C_MINOR(0, true, "Cm"),
    C_SHARP_MINOR(1, true, "C#m"),
    D_MINOR(2, true, "Dm"),
    E_FLAT_MINOR(3, true, "Ebm"),
    E_MINOR(4, true, "Em"),
    F_MINOR(5, true, "Fm"),
    F_SHARP_MINOR(6, true, "F#m"),
    G_MINOR(7, true, "Gm"),
    G_SHARP_MINOR(8, true, "G#m"),
    A_MINOR(9, true, "Am"),
    B_FLAT_MINOR(10, true, "Bbm"),
    B_MINOR(11, true, "Bm");

    private static final String EXAMPLES = "예: Am, A minor, F#m, Bb major, A_MINOR";
    private static final int[] NATURAL_PITCH = {9, 11, 0, 2, 4, 5, 7}; // A B C D E F G

    private final int pitchClass;
    private final boolean minor;
    private final String displayName;

    MusicalKey(int pitchClass, boolean minor, String displayName) {
        this.pitchClass = pitchClass;
        this.minor = minor;
        this.displayName = displayName;
    }

    public int pitchClass() {
        return pitchClass;
    }

    public boolean isMinor() {
        return minor;
    }

    public String displayName() {
        return displayName;
    }

    /** null·공백은 "값 없음"으로 null 을 돌려준다(선택 입력). 해석할 수 없으면 400 대상 예외. */
    public static MusicalKey parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parse(raw);
    }

    public static MusicalKey parse(String raw) {
        String s = raw.strip().replace('♯', '#').replace('♭', 'b').replaceAll("\\s+", " ");
        for (MusicalKey key : values()) {
            if (key.name().equals(s)) {
                return key;
            }
        }
        if (s.isEmpty()) {
            throw invalid(raw);
        }

        char letter = s.charAt(0);
        int letterIndex = Character.toUpperCase(letter) - 'A';
        if (letterIndex < 0 || letterIndex > 6 || !isAsciiLetter(letter)) {
            throw invalid(raw);
        }
        int pitch = NATURAL_PITCH[letterIndex];
        String rest = s.substring(1);

        // 변화표 — 기호(#, b) 또는 단어(sharp, flat). 조 표기 중 'b' 로 시작하는 말은 없으므로 b 는 항상 플랫이다.
        if (rest.startsWith("#")) {
            pitch += 1;
            rest = rest.substring(1);
        } else if (rest.startsWith("b")) {
            pitch -= 1;
            rest = rest.substring(1);
        } else if (startsWithWord(rest, "sharp")) {
            pitch += 1;
            rest = rest.strip().substring("sharp".length());
        } else if (startsWithWord(rest, "flat")) {
            pitch -= 1;
            rest = rest.strip().substring("flat".length());
        }

        String mode = rest.strip();
        boolean isMinor;
        if (mode.isEmpty()) {
            if (Character.isLowerCase(letter)) {
                throw ambiguous(raw); // 소문자 단독 — 단조 표기 관례와 충돌
            }
            isMinor = false;
        } else if (mode.equals("m")) {
            isMinor = true;
        } else if (mode.equals("M")) {
            throw ambiguous(raw); // AM — 장조인지 Am 의 대문자 입력인지 알 수 없다
        } else {
            String word = mode.toLowerCase(Locale.ROOT);
            if (word.equals("min") || word.equals("minor")) {
                isMinor = true;
            } else if (word.equals("maj") || word.equals("major")) {
                isMinor = false;
            } else {
                throw invalid(raw);
            }
        }
        return of(Math.floorMod(pitch, 12), isMinor);
    }

    public static MusicalKey of(int pitchClass, boolean minor) {
        for (MusicalKey key : values()) {
            if (key.pitchClass == pitchClass && key.minor == minor) {
                return key;
            }
        }
        throw new IllegalArgumentException("pitchClass 는 0~11: " + pitchClass);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'G') || (c >= 'a' && c <= 'g');
    }

    private static boolean startsWithWord(String rest, String word) {
        return rest.strip().toLowerCase(Locale.ROOT).startsWith(word);
    }

    private static InvalidMusicAttributeException invalid(String raw) {
        return new InvalidMusicAttributeException("musicalKey 를 해석할 수 없습니다: '" + raw + "' (" + EXAMPLES + ")");
    }

    private static InvalidMusicAttributeException ambiguous(String raw) {
        return new InvalidMusicAttributeException("musicalKey 표기가 모호합니다: '" + raw + "' — 장조는 A 또는 A major, 단조는 Am 또는 A minor 로 입력하세요");
    }
}
