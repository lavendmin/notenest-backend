package com.notenest.search;

import com.notenest.domain.MusicalKey;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 검색 문서를 만들 원본 값 — MariaDB(원본)에서 JPQL 프로젝션으로 읽는다. 미디어 바이트·객체 URL 은 포함하지 않는다.
 */
public record MusicSearchSource(
        UUID musicUuid,
        LocalDateTime createdAt,
        String title,
        String subtitle,
        String details,
        String hashtag,
        String majorGenre,
        String sellerNickname,
        int status,
        Long startingPrice,
        Long currentHighestBid,
        LocalDateTime auctionEndTime,
        int likeCount,
        Integer bpm,
        MusicalKey musicalKey,
        String coverObjectKey) {
}
