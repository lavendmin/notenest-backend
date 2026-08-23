package com.notenest.repository;

import com.notenest.domain.Likes;
import com.notenest.domain.Music;
import com.notenest.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface LikeRepository extends JpaRepository<Likes, UUID> {

    Likes findByUserAndMusic(User user, Music music);

    List<Likes> findByUser(User user);

    @Query("SELECT COUNT(l) FROM Likes l WHERE l.user.userUUID = :userId AND l.music.musicUuid = :musicId")
    int countByUserIdAndMusicId(@Param("userId") UUID userId, @Param("musicId") UUID musicId);

    // 목록 좋아요 여부 N+1 제거 — 한 사용자가 주어진 곡 집합 중 좋아요한 곡 ID만 IN 조회 1회로 가져온다.
    @Query("SELECT l.music.musicUuid FROM Likes l WHERE l.user.userUUID = :userId AND l.music.musicUuid IN :musicIds")
    List<UUID> findLikedMusicIds(@Param("userId") UUID userId, @Param("musicIds") Collection<UUID> musicIds);

}
