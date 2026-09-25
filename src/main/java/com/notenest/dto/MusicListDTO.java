
package com.notenest.dto;

import com.notenest.domain.Music;
import com.notenest.storage.MediaUrlIssuer;
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
    private Long startingPrice;
    private Long currentHighestBid;
    private LocalDateTime auctionEndTime;
    private int likeCount;
    // 목록류 응답은 커버만 싣고 음원(audio)은 싣지 않는다. 커버는 coverUrl, image 는 키 없는 기존 곡의 전이 기간 fallback.
    private byte[] image;
    private String coverUrl;
    private LocalDateTime createdAt;


    // Music 엔티티를 MusicDTO로 변환하는 메서드
    public static MusicListDTO fromMusic(Music music, MediaUrlIssuer mediaUrls) {
        MusicListDTO musicListDTO = new MusicListDTO();
        musicListDTO.setMusicUuid(music.getMusicUuid());
        musicListDTO.setTitle(music.getTitle());
        musicListDTO.setImage(MediaUrlIssuer.legacyCoverBytes(music));
        musicListDTO.setCoverUrl(mediaUrls.coverUrl(music.getCover()));

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
