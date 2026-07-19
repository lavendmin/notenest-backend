package com.notenest.repository;

import com.notenest.domain.Music;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;

import org.springframework.data.domain.Pageable;
import java.util.UUID;

@Repository
public interface MusicRepository extends JpaRepository<Music, UUID>, JpaSpecificationExecutor<Music> {


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
