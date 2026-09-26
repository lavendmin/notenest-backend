package com.notenest.domain;

/**
 * [NB5] 곡 템포(BPM) 계약 — 40 이상 250 이하의 정수. 값이 없는(기존) 곡은 null.
 *
 * 소수 BPM(예: 128.5)은 받지 않는다. JSON 에서 소수가 정수 필드로 오면 Jackson 이 절삭하지 않고 거부한다
 * (spring.jackson.deserialization.accept-float-as-int=false, NB2 금액 계약과 같은 설정).
 * 하프/더블타임(70 ↔ 140) 동치는 다루지 않는다.
 */
public final class Bpm {

    public static final int MIN = 40;
    public static final int MAX = 250;

    private Bpm() {
    }

    /** null 은 통과시킨다(선택 입력). 범위를 벗어나면 400 대상 예외. */
    public static Integer requireValidOrNull(Integer bpm, String field) {
        if (bpm != null && (bpm < MIN || bpm > MAX)) {
            throw new InvalidMusicAttributeException(field + "는 " + MIN + "~" + MAX + " 사이의 정수여야 합니다: " + bpm);
        }
        return bpm;
    }
}
