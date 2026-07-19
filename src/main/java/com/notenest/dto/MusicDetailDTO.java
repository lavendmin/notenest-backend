package com.notenest.dto;

import com.notenest.domain.Music;
import lombok.*;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class MusicDetailDTO {
    private UUID musicUuid;
    private String nickName; // 음악을 등록한 사용자의 닉네임
    private String title;
    private String subtitle;
    private String majorGenre;
    private Double startingPrice;
    private Integer musicPeriod;
    private String details;
    private String hashtag;
    private byte[] image;
    private byte[] audio;
    private Double currentHighestBid;
    private LocalDateTime auctionEndTime;
    private Boolean popularComposer;
    private Boolean steadyWorkComposer;
    private Boolean hitSongComposer;
    private Page<BidListDTO> bidPrices;
    private Boolean showAllBids;

    public static MusicDetailDTO fromMusic(Music music, Page<BidListDTO> bids) {
        MusicDetailDTO musicDetailDTO = new MusicDetailDTO();
        musicDetailDTO.setMusicUuid(music.getMusicUuid());
        musicDetailDTO.setNickName(music.getUser().getNickname());
        musicDetailDTO.setTitle(music.getTitle());
        musicDetailDTO.setSubtitle(music.getSubtitle());
        musicDetailDTO.setMajorGenre(music.getMajorGenre());
        musicDetailDTO.setStartingPrice(music.getStartingPrice());
        musicDetailDTO.setMusicPeriod(music.getMusicPeriod());
        musicDetailDTO.setDetails(music.getDetails());
        musicDetailDTO.setHashtag(music.getHashtag());
        // 이미지를 byte[] 그대로 설정
        musicDetailDTO.setImage(music.getImage());

        // 오디오를 byte[] 그대로 설정
        musicDetailDTO.setAudio(music.getAudio());

        musicDetailDTO.setPopularComposer(music.getPopularComposer());
        musicDetailDTO.setSteadyWorkComposer(music.getSteadyWorkComposer());
        musicDetailDTO.setHitSongComposer(music.getHitSongComposer());


        musicDetailDTO.setAuctionEndTime(music.getAuctionEndTime());

        musicDetailDTO.setBidPrices(bids.map(bid -> {
            BidListDTO bidListDTO = new BidListDTO();
            bidListDTO.setBidUuid(bid.getBidUuid());
            bidListDTO.setPrice(bid.getPrice());
            bidListDTO.setCreatedAt(bid.getCreatedAt());
            return bidListDTO;
        }));
        musicDetailDTO.setShowAllBids(music.getShowAllBids());

        return musicDetailDTO;
    }
}
