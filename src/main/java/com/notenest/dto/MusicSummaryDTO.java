package com.notenest.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 공개 경매 곡 목록 전용 DTO.
 *
 * 상세/다운로드 DTO와 분리하며 <b>audio(음원) 필드를 두지 않는다</b> — 목록 조회가
 * 음원 LOB 를 절대 실어 나르지 않도록 타입 수준에서 차단한다.
 *
 * 커버는 coverUrl(객체 저장소의 만료 시간 있는 URL)로 준다. image(base64)는 객체 키가 없는
 * 기존 곡(백필 전)만 채우는 전이 기간 fallback 이며, LOB 컬럼 삭제 때 함께 제거한다.
 * 객체 키 자체(coverObjectKey)는 URL 발급용 내부 값이라 응답에 싣지 않는다.
 *
 * JSON 필드명은 기존 MusicDTO 와 동일하게 유지해 응답 계약을 보존한다.
 * likedByUser 는 프로젝션 이후 좋아요 IN 조회 결과로 채운다.
 */
@Getter
@Setter
@NoArgsConstructor
public class MusicSummaryDTO {
    private UUID musicUuid;
    private String title;
    private Long startingPrice;
    private String userNickName;
    private Long currentHighestBid;
    private LocalDateTime auctionEndTime;
    private int likeCount;
    private byte[] image;
    private String coverUrl;
    @JsonIgnore
    private String coverObjectKey;
    private boolean likedByUser;

    // QueryDSL Projections.constructor 대상 — SELECT 절에 audio 는 포함하지 않는다.
    public MusicSummaryDTO(UUID musicUuid, String title, Long startingPrice, String userNickName,
                           Long currentHighestBid, LocalDateTime auctionEndTime, int likeCount, byte[] image,
                           String coverObjectKey) {
        this.musicUuid = musicUuid;
        this.title = title;
        this.startingPrice = startingPrice;
        this.userNickName = userNickName;
        this.currentHighestBid = currentHighestBid;
        this.auctionEndTime = auctionEndTime;
        this.likeCount = likeCount;
        this.image = image;
        this.coverObjectKey = coverObjectKey;
    }
}
