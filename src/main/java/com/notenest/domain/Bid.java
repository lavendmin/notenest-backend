package com.notenest.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "bid")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Bid {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "bid_uuid")
    private UUID bidUuid;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "music_uuid", nullable = false)
    private Music music;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_uuid", nullable = false)
    private User user;

    @Column(name = "price")
    private double price;

    //impUid 필드 (결제 고유 ID)
    @Column(name = "imp_uid")
    private String impUid;

    // 낙찰 내역 위한 상태 : "PENDING"(결제 대기), "COMPLETED"(결제 완료),"FAILED"(경매 무산)
    @Column(name = "status")
    private String status;

    // 입찰자 낙찰 성공 이메일 발송 상태
    @Column(name = "bidder_email_sent", nullable = false)
    private boolean bidderEmailSent = false;

    // 작곡가 낙찰 성공 이메일 발송 상태
    @Column(name = "composer_email_sent", nullable = false)
    private boolean composerEmailSent = false;
}
