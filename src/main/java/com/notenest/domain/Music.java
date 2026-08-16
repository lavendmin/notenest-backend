package com.notenest.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
// 스케줄러가 "마감 시각 지난 곡"을 인덱스로 선별하도록 (Phase 1 — 종료 대상 조회 쿼리화)
@Table(name = "music", indexes = @Index(name = "idx_music_auction_end_time", columnList = "auction_end_time"))
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Music {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(name = "music_uuid", updatable = false, nullable = false)
    private UUID musicUuid;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "title")
    private String title;

    @Column(name = "subtitle")
    private String subtitle;

    @Column(name = "major_genre")
    private String majorGenre;

    @Column(name = "starting_price")
    private Double startingPrice;

    @Column(name = "music_period")
    private Integer musicPeriod;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "hashtag")
    private String hashtag;

    // 이미지와 오디오는 데이터베이스에 큰 크기의 데이터로 저장될 수 있도록 @Lob 어노테이션 사용
    @Lob
    @Column(name = "image", columnDefinition = "LONGBLOB")
    private byte[] image;

    @Lob
    @Column(name = "audio", columnDefinition = "LONGBLOB")
    private byte[] audio;

    // 다대일(Many-to-One) 관계 설정
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_uuid", nullable = false)
    private User user;

    // 일대다(One-to-Many) 관계 설정
    @OneToMany(mappedBy = "music", cascade = CascadeType.ALL)
    private List<Bid> bids = new ArrayList<>();

    // 현재 최고 입찰가를 저장할 속성
    @Column(name = "current_highest_bid")
    private Double currentHighestBid;

    @Column(name = "auction_end_time")
    private LocalDateTime auctionEndTime;

    // 찜 개수
    @Column(name = "like_count")
    private int likeCount;

    //입찰 리스트 개수
    @Column(name = "show_all_bids", nullable = false)
    private Boolean showAllBids;

    // 차트 진입 횟수가 상위 50%
    @Column(name = "popular_composer", nullable = false)
    private Boolean popularComposer;

    // 10년동안 3번 이상 차트 진입
    @Column(name = "steady_work_composer", nullable = false)
    private Boolean steadyWorkComposer;

    // 차트 진입 횟수가 1번 이상
    @Column(name = "hit_song_composer", nullable = false)
    private Boolean hitSongComposer;

    // 작곡가 경매 실패 이메일 발송 상태
    @Column(name = "auction_failure_email_sent", nullable = false)
    private boolean auctionFailureEmailSent = false;

    @Column(name = "status", nullable = false)
    private int status = 0; // 0: 낙찰되지 않음, 1: 낙찰됨


    //auctionEndTime 계산
    public void setMusicPeriod(Integer musicPeriod) {
        this.musicPeriod = musicPeriod;
        if (this.createdAt != null && this.musicPeriod != null) {
            this.auctionEndTime = this.createdAt.plusDays(this.musicPeriod);
        }
    }
}
