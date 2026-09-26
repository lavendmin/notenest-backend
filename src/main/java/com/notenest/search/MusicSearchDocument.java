package com.notenest.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.notenest.dto.MusicSummaryDTO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

/**
 * [NB5] 검색 색인 문서 — 목록 화면용 read model. 매핑은 resources/elasticsearch/music-index.json.
 *
 * MariaDB 가 거래·상태의 원본이고 이 문서는 다시 만들 수 있는 사본이다. 커버는 객체 키만 두고 URL 은 응답 직전에 발급한다
 * (presigned URL 을 색인하지 않는다). 시각은 ISO-8601 문자열로 두어 DB 값의 소수 초까지 그대로 응답에 돌려준다.
 *
 * [Phase 4] sourceHash — 문서의 나머지 필드로 계산한 결정적 SHA-256. 대조 작업이 DB 에서 다시 계산한 값과 색인에 저장된 값을
 * 비교해 내용 불일치를 찾는다. 같은 원본이면 JVM·시각과 무관하게 같은 값이다.
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
        @JsonProperty("cover_key") String coverKey,
        @JsonProperty("source_hash") String sourceHash) {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public static MusicSearchDocument from(MusicSearchSource s) {
        String musicId = s.musicUuid().toString();
        String createdAt = format(s.createdAt());
        // 가격 필터 기준 — 목록 계약과 같이 최고 입찰가, 없으면 시작가
        Long price = s.currentHighestBid() != null ? s.currentHighestBid() : s.startingPrice();
        String auctionEndTime = format(s.auctionEndTime());
        String musicalKey = s.musicalKey() == null ? null : s.musicalKey().name();
        String hash = hash(musicId, createdAt, s.title(), s.subtitle(), s.details(), s.hashtag(), s.majorGenre(),
                s.sellerNickname(), s.status(), price, s.startingPrice(), s.currentHighestBid(), s.likeCount(),
                auctionEndTime, s.bpm(), musicalKey, s.coverObjectKey());
        return new MusicSearchDocument(musicId, createdAt, s.title(), s.subtitle(), s.details(), s.hashtag(),
                s.majorGenre(), s.sellerNickname(), s.status(), price, s.startingPrice(), s.currentHighestBid(),
                s.likeCount(), auctionEndTime, s.bpm(), musicalKey, s.coverObjectKey(), hash);
    }

    /** 목록 응답 DTO — 커버 URL·좋아요 여부는 서비스가 채운다(비검색 경로와 같은 후처리). */
    public MusicSummaryDTO toSummary() {
        return new MusicSummaryDTO(UUID.fromString(musicId), title, startingPrice, seller, currentHighestBid,
                auctionEndTime == null ? null : LocalDateTime.parse(auctionEndTime, ISO), likeCount, coverKey);
    }

    /**
     * 필드 값을 정해진 순서로 이어 SHA-256. 필드 구분자는 U+001F, null 은 U+0000 으로 적어 "null" 문자열·빈 문자열과 구별한다.
     */
    static String hash(Object... fields) {
        StringBuilder canonical = new StringBuilder();
        for (Object field : fields) {
            canonical.append(field == null ? "\u0000" : field.toString()).append('\u001F');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String format(LocalDateTime t) {
        return t == null ? null : ISO.format(t);
    }
}
