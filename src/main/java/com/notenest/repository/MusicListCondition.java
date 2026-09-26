package com.notenest.repository;

import com.notenest.domain.Bpm;
import com.notenest.domain.InvalidMusicAttributeException;
import com.notenest.domain.MusicalKey;

/**
 * 공개 경매 곡 목록(GET /api/music/filter)의 조회 조건. 진행 중(status=0) 조건은 항상 붙으므로 여기 두지 않는다.
 *
 * [NB5] bpmMin·bpmMax(포함 범위)와 musicalKey(정확 일치)를 추가했다. 값이 없는 곡(null)은 해당 필터가 있으면 제외된다.
 * 검증은 생성 시점({@link #of})에 한다 — 범위 밖 BPM, min > max, 해석할 수 없는 키는 400 대상 예외.
 */
public record MusicListCondition(
        String majorGenre,
        String hashtags,
        Long minPrice,
        Long maxPrice,
        Integer bpmMin,
        Integer bpmMax,
        MusicalKey musicalKey,
        String searchTerm) {

    public static MusicListCondition of(String majorGenre, String hashtags, Long minPrice, Long maxPrice,
                                        Integer bpmMin, Integer bpmMax, String musicalKey, String searchTerm) {
        Bpm.requireValidOrNull(bpmMin, "bpmMin");
        Bpm.requireValidOrNull(bpmMax, "bpmMax");
        if (bpmMin != null && bpmMax != null && bpmMin > bpmMax) {
            throw new InvalidMusicAttributeException("bpmMin 은 bpmMax 보다 클 수 없습니다: " + bpmMin + " > " + bpmMax);
        }
        return new MusicListCondition(majorGenre, hashtags, minPrice, maxPrice, bpmMin, bpmMax,
                MusicalKey.parseOrNull(musicalKey), searchTerm);
    }
}
