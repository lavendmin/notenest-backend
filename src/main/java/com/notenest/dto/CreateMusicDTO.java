package com.notenest.dto;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateMusicDTO {
    private String title;
    private String subtitle;
    private String majorGenre;
    private Long startingPrice;
    private Integer musicPeriod;
    private String details;
    private String hashtag;
    // [NB5] 선택 입력. bpm 은 40~250 정수, musicalKey 는 영문 표기(Am, A minor, F#m…) 또는 enum 이름 — 서버가 정규화한다.
    private Integer bpm;
    private String musicalKey;
    private UUID userUuid; // 음악을 등록한 사용자의 UUID
    private String nickName; // 음악을 등록한 사용자의 닉네임
    private Long currentHighestBid;
    private LocalDateTime auctionEndTime;
    private Boolean showAllBids; // 입찰 내역을 모두 보여줄지 여부
    private Boolean popularComposer;
    private Boolean steadyWorkComposer;
    private Boolean hitSongComposer;
}
