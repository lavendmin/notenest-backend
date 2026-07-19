package com.notenest.dto;

import com.notenest.domain.Music;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class MusicDTO {
    private UUID musicUuid;
    private String title;
    private Double startingPrice;
    private String userNickName;
    private Double currentHighestBid;
    private LocalDateTime auctionEndTime;
    private boolean likedByUser;
    private int likeCount;
    private byte[] image;
    private byte[] audio;


    // Music 엔티티를 MusicDTO로 변환하는 메서드
    public static MusicDTO fromMusic(Music music, boolean likedByUser) {
        MusicDTO musicDTO = new MusicDTO();
        musicDTO.setMusicUuid(music.getMusicUuid());
        musicDTO.setTitle(music.getTitle());
        musicDTO.setStartingPrice(music.getStartingPrice());
        // 이미지를 byte[] 그대로 설정
        musicDTO.setImage(music.getImage());
        // 오디오를 byte[] 그대로 설정
        musicDTO.setAudio(music.getAudio());

        // 사용자가 null인 경우에 대한 예외 처리 추가
        if (music.getUser() != null) {
            musicDTO.setUserNickName(music.getUser().getNickname());
        } else {
            musicDTO.setUserNickName(null); // 사용자가 null인 경우 null로 설정
        }
        musicDTO.setCurrentHighestBid(music.getCurrentHighestBid()); // 최고 입찰가 설정
        musicDTO.setAuctionEndTime(music.getAuctionEndTime()); // 경매 마감일 설정
        musicDTO.setLikedByUser(likedByUser); // 사용자의 찜 여부 설정
        musicDTO.setLikeCount(music.getLikeCount()); // 좋아요 수 설정
        return musicDTO;
    }

}
