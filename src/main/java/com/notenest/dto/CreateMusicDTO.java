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
    private Double startingPrice;
    private Integer musicPeriod;
    private String details;
    private String hashtag;
    private byte[] image;
    private byte[] audio;
    private UUID userUuid; // 음악을 등록한 사용자의 UUID
    private String nickName; // 음악을 등록한 사용자의 닉네임
    private Double currentHighestBid;
    private LocalDateTime auctionEndTime;
    private Boolean showAllBids; // 입찰 내역을 모두 보여줄지 여부
    private Boolean popularComposer;
    private Boolean steadyWorkComposer;
    private Boolean hitSongComposer;
}
