package com.notenest.repository;

import com.notenest.domain.Likes;
import com.notenest.domain.Music;
import com.notenest.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LikeRepository extends JpaRepository<Likes, UUID> {

    Likes findByUserAndMusic(User user, Music music);

    List<Likes> findByUser(User user);

    @Query("SELECT COUNT(l) FROM Likes l WHERE l.user.userUUID = :userId AND l.music.musicUuid = :musicId")
    int countByUserIdAndMusicId(@Param("userId") UUID userId, @Param("musicId") UUID musicId);


}
