package com.notenest.dto;

import com.notenest.domain.Music;
import com.notenest.storage.MediaUrlIssuer;
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
    private Long startingPrice;
    private Integer musicPeriod;
    private String details;
    private String hashtag;
    // 커버·미리듣기는 만료 시간 있는 URL. image(base64)는 커버 키가 없는 기존 곡만 채우는 전이 기간 fallback.
    // 미리듣기가 없는 기존 곡은 previewUrl 이 null 이다(전체 데모로 대체하지 않는다).
    private byte[] image;
    private String coverUrl;
    private String previewUrl;
    // 전체 음원(audio)·전체 데모 키는 상세에 싣지 않는다 — 전체 데모는 /api/mypage/download 에서 접근 권한 확인 후 제공한다.
    private Long currentHighestBid;
    private LocalDateTime auctionEndTime;
    private Boolean popularComposer;
    private Boolean steadyWorkComposer;
    private Boolean hitSongComposer;
    private Page<BidListDTO> bidPrices;
    private Boolean showAllBids;

    public static MusicDetailDTO fromMusic(Music music, Page<BidListDTO> bids, MediaUrlIssuer mediaUrls) {
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
        musicDetailDTO.setImage(MediaUrlIssuer.legacyCoverBytes(music));
        musicDetailDTO.setCoverUrl(mediaUrls.coverUrl(music.getCover()));
        musicDetailDTO.setPreviewUrl(mediaUrls.previewUrl(music.getPreview()));

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
