package com.notenest.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateMusicDTO {
    private String title;
    private String subtitle;
    private String majorGenre;
    private String details;
    private String hashtag;
    private byte[] image;
    private Boolean showAllBids; // 입찰 내역을 모두 보여줄지 여부
}
