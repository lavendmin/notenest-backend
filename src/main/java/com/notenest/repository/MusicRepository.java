package com.notenest.repository;

import com.notenest.domain.Music;
import com.notenest.search.MusicSearchSource;
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
public interface MusicRepository extends JpaRepository<Music, UUID>, JpaSpecificationExecutor<Music>, MusicRepositoryCustom {

    // [경매 마감 잡] 아직 마감되지 않았고(status=0) 종료 시각이 지난 곡의 UUID만 조회.
    // status=0 조건으로 이미 마감된 곡의 재선정을 막아, 마감을 한 곡당 한 번만 수행한다.
    // (Lob 컬럼을 SELECT 절에서 제외해 대상 식별 단계에서 이미지·음원을 로딩하지 않는다.)
    @Query("SELECT m.musicUuid FROM Music m WHERE m.status = 0 AND m.auctionEndTime IS NOT NULL AND m.auctionEndTime < :now")
    List<UUID> findUuidsToClose(@Param("now") LocalDateTime now);

    // [NB5] 검색 색인 전체 재생성용 원본 — 목록·검색에 필요한 열만 읽는다(미디어 바이트 없음, 종료 곡 포함: 필터는 색인에서 건다).
    @Query("SELECT new com.notenest.search.MusicSearchSource(m.musicUuid, m.createdAt, m.title, m.subtitle, m.details, m.hashtag, "
            + "m.majorGenre, u.nickname, m.status, m.startingPrice, m.currentHighestBid, m.auctionEndTime, m.likeCount, "
            + "m.bpm, m.musicalKey, m.cover.objectKey) FROM Music m JOIN m.user u")
    List<MusicSearchSource> findAllSearchSources();

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
