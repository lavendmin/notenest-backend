package com.notenest.repository;

import com.notenest.domain.Board;
import com.notenest.domain.Music;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BoardRepository extends JpaRepository<Board, UUID> {
    List<Board> findAllByMusic(Music music);
}
