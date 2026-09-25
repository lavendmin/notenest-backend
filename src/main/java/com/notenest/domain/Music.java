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

    // [NB1] 곡 UUID 는 저장 전에 정한다 — 객체 키(music/{musicUuid}/...)로 파일을 먼저 올린 뒤 DB 에 저장하기 때문이다.
    // 등록 서비스가 미리 지정하고, 지정하지 않은 경로(시드·테스트 픽스처 등)는 저장 직전에 여기서 발급한다.
    @Id
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

    // 금액은 원(KRW) 단위 정수(long). 소수 통화·배율(*100) 없음 — NB2 금액 계약.
    @Column(name = "starting_price")
    private Long startingPrice;

    @Column(name = "music_period")
    private Integer musicPeriod;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "hashtag")
    private String hashtag;

    // [NB1] 곡 미디어는 객체 저장소(S3, private 버킷)에 두고 DB 에는 키·메타데이터만 남긴다.
    // 예전 image/audio LOB 컬럼은 백필·대조 후 nb1-03 마이그레이션으로 삭제했다.
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "objectKey", column = @Column(name = "cover_object_key", length = 512)),
            @AttributeOverride(name = "contentType", column = @Column(name = "cover_content_type", length = 100)),
            @AttributeOverride(name = "size", column = @Column(name = "cover_size")),
            @AttributeOverride(name = "originalName", column = @Column(name = "cover_original_name", length = 255))
    })
    private MediaObject cover;

    // 기존 곡은 미리듣기가 없을 수 있다(null). 신규 곡의 미리듣기 필수는 등록 API 검증으로 집행한다.
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "objectKey", column = @Column(name = "preview_object_key", length = 512)),
            @AttributeOverride(name = "contentType", column = @Column(name = "preview_content_type", length = 100)),
            @AttributeOverride(name = "size", column = @Column(name = "preview_size")),
            @AttributeOverride(name = "originalName", column = @Column(name = "preview_original_name", length = 255))
    })
    private MediaObject preview;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "objectKey", column = @Column(name = "full_demo_object_key", length = 512)),
            @AttributeOverride(name = "contentType", column = @Column(name = "full_demo_content_type", length = 100)),
            @AttributeOverride(name = "size", column = @Column(name = "full_demo_size")),
            @AttributeOverride(name = "originalName", column = @Column(name = "full_demo_original_name", length = 255))
    })
    private MediaObject fullDemo;

    // 다대일(Many-to-One) 관계 설정
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_uuid", nullable = false)
    private User user;

    // 일대다(One-to-Many) 관계 설정
    @OneToMany(mappedBy = "music", cascade = CascadeType.ALL)
    private List<Bid> bids = new ArrayList<>();

    // 현재 최고 입찰가를 저장할 속성 — 원(KRW) 단위 정수(long)
    @Column(name = "current_highest_bid")
    private Long currentHighestBid;

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


    @PrePersist
    void assignUuidIfAbsent() {
        if (musicUuid == null) {
            musicUuid = UUID.randomUUID();
        }
    }

    //auctionEndTime 계산
    public void setMusicPeriod(Integer musicPeriod) {
        this.musicPeriod = musicPeriod;
        if (this.createdAt != null && this.musicPeriod != null) {
            this.auctionEndTime = this.createdAt.plusDays(this.musicPeriod);
        }
    }
}
