package com.notenest.repository;

import com.notenest.domain.Music;
import com.notenest.dto.MusicDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;

import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface MusicRepository extends JpaRepository<Music, UUID>, JpaSpecificationExecutor<Music> {

    // [Phase 1] 스케줄러용 — 마감 시각이 지난 곡의 UUID만 조회 (Lob 컬럼을 SELECT 절에서 제외).
    // 기존 findAll() 후 자바 루프 필터링과 동일한 대상 집합을 DB가 인덱스로 선별한다.
    @Query("SELECT m.musicUuid FROM Music m WHERE m.auctionEndTime IS NOT NULL AND m.auctionEndTime < :now")
    List<UUID> findEndedMusicUuids(@Param("now") LocalDateTime now);

    // [Phase 1] 목록용 DTO 프로젝션 — 목록에 필요한 컬럼만 SELECT (audio/image 제외)
    @Query("""
            SELECT new com.notenest.dto.MusicDTO(
                m.musicUuid, m.title, m.startingPrice, u.nickname,
                m.currentHighestBid, m.auctionEndTime, m.likeCount)
            FROM Music m LEFT JOIN m.user u
            WHERE m.status = 0
            ORDER BY m.createdAt DESC""")
    Page<MusicDTO> findOngoingMusicSummariesByCreatedAtDesc(Pageable pageable);

    // 최신 순으로 곡 리스트 가져오기 (진행 중인 곡만)
    @Query("SELECT m FROM Music m WHERE m.status = 0 ORDER BY m.createdAt DESC")
    Page<Music> findAllOngoingMusicByOrderByCreatedAtDesc(Pageable pageable);

    // 가격 순으로 곡 리스트 가져오기 (진행 중인 곡만)
    @Query("SELECT m FROM Music m WHERE m.status = 0 ORDER BY m.currentHighestBid DESC")
    Page<Music> findAllOngoingMusicByOrderByCurrentHighestBidDesc(Pageable pageable);

    // 좋아요 순으로 곡 리스트 가져오기 (진행 중인 곡만)
    @Query("SELECT m FROM Music m WHERE m.status = 0 ORDER BY m.likeCount DESC")
    Page<Music> findAllOngoingMusicByOrderByLikeCountDesc(Pageable pageable);
}
