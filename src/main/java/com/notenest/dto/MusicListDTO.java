
package com.notenest.dto;

import com.notenest.domain.Music;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class MusicListDTO {
    private UUID musicUuid;
    private String title;
    private String userNickName;
    private Double startingPrice;
    private Double currentHighestBid;
    private LocalDateTime auctionEndTime;
    private int likeCount;
    private byte[] image;
    private byte[] audio;
    private LocalDateTime createdAt;


    // Music 엔티티를 MusicDTO로 변환하는 메서드
    public static MusicListDTO fromMusic(Music music) {
        MusicListDTO musicListDTO = new MusicListDTO();
        musicListDTO.setMusicUuid(music.getMusicUuid());
        musicListDTO.setTitle(music.getTitle());
        // 이미지를 byte[] 그대로 설정
        musicListDTO.setImage(music.getImage());
        // 오디오를 byte[] 그대로 설정
        musicListDTO.setAudio(music.getAudio());

        // 사용자가 null인 경우에 대한 예외 처리 추가
        if (music.getUser() != null) {
            musicListDTO.setUserNickName(music.getUser().getNickname());
        } else {
            musicListDTO.setUserNickName(null); // 사용자가 null인 경우 null로 설정
        }
        musicListDTO.setStartingPrice(music.getStartingPrice());
        musicListDTO.setCurrentHighestBid(music.getCurrentHighestBid()); // 최고 입찰가 설정
        musicListDTO.setAuctionEndTime(music.getAuctionEndTime()); // 경매 마감일 설정
        musicListDTO.setLikeCount(music.getLikeCount()); // 찜 개수
        musicListDTO.setCreatedAt(music.getCreatedAt());
        return musicListDTO;
    }
}
