package com.notenest.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.notenest.dto.MusicSummaryDTO;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * [NB5] 검색 색인 문서 — 목록 화면용 read model. 매핑은 resources/elasticsearch/music-index.json.
 *
 * MariaDB 가 거래·상태의 원본이고 이 문서는 다시 만들 수 있는 사본이다. 커버는 객체 키만 두고 URL 은 응답 직전에 발급한다
 * (presigned URL 을 색인하지 않는다). 시각은 ISO-8601 문자열로 두어 DB 값의 소수 초까지 그대로 응답에 돌려준다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MusicSearchDocument(
        @JsonProperty("music_id") String musicId,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("title") String title,
        @JsonProperty("subtitle") String subtitle,
        @JsonProperty("details") String details,
        @JsonProperty("hashtag") String hashtag,
        @JsonProperty("genre") String genre,
        @JsonProperty("seller") String seller,
        @JsonProperty("status") int status,
        @JsonProperty("price") Long price,
        @JsonProperty("starting_price") Long startingPrice,
        @JsonProperty("current_highest_bid") Long currentHighestBid,
        @JsonProperty("like_count") int likeCount,
        @JsonProperty("auction_end_time") String auctionEndTime,
        @JsonProperty("bpm") Integer bpm,
        @JsonProperty("musical_key") String musicalKey,
        @JsonProperty("cover_key") String coverKey) {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public static MusicSearchDocument from(MusicSearchSource s) {
        return new MusicSearchDocument(
                s.musicUuid().toString(), format(s.createdAt()), s.title(), s.subtitle(), s.details(), s.hashtag(),
                s.majorGenre(), s.sellerNickname(), s.status(),
                // 가격 필터 기준 — 목록 계약과 같이 최고 입찰가, 없으면 시작가
                s.currentHighestBid() != null ? s.currentHighestBid() : s.startingPrice(),
                s.startingPrice(), s.currentHighestBid(), s.likeCount(), format(s.auctionEndTime()), s.bpm(),
                s.musicalKey() == null ? null : s.musicalKey().name(), s.coverObjectKey());
    }

    /** 목록 응답 DTO — 커버 URL·좋아요 여부는 서비스가 채운다(비검색 경로와 같은 후처리). */
    public MusicSummaryDTO toSummary() {
        return new MusicSummaryDTO(UUID.fromString(musicId), title, startingPrice, seller, currentHighestBid,
                auctionEndTime == null ? null : LocalDateTime.parse(auctionEndTime, ISO), likeCount, coverKey);
    }

    private static String format(LocalDateTime t) {
        return t == null ? null : ISO.format(t);
    }
}
