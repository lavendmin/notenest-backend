package com.notenest.repository;

import com.notenest.domain.Music;
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

    // [NB1 백필] 객체 키가 없는데 LOB 가 남아 있는 곡 — 커버(image) 또는 전체 데모(audio) 중 하나라도 이전이 필요한 곡.
    // 이미 키가 채워진 자산은 대상에서 빠지므로 재실행하면 남은 곡만 다시 처리한다.
    @Query("SELECT m.musicUuid FROM Music m WHERE (m.cover.objectKey IS NULL AND m.image IS NOT NULL)"
            + " OR (m.fullDemo.objectKey IS NULL AND m.audio IS NOT NULL) ORDER BY m.musicUuid")
    List<UUID> findUuidsNeedingMediaBackfill();

    // [NB1 백필 검증] 전체 곡 UUID — 곡마다 하나씩 불러 대조한다(LOB 를 한꺼번에 올리지 않기 위해).
    @Query("SELECT m.musicUuid FROM Music m ORDER BY m.musicUuid")
    List<UUID> findAllUuids();

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
