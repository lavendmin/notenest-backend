package com.notenest.service;

import com.notenest.domain.Music;
import com.notenest.dto.CreateMusicDTO;
import com.notenest.dto.MusicDTO;
import com.notenest.dto.MusicDetailDTO;
import com.notenest.dto.UpdateMusicDTO;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface MusicService {

    Music createMusic(CreateMusicDTO createMusicDTO, String loggedInUserEmail);

    void deleteMusic(UUID musicUuid, String loggedInUserEmail);

    Music updateMusic(UUID musicUuid, UpdateMusicDTO updateMusicDTO, String loggedInUserEmail);

    MusicDetailDTO getMusicDetail(UUID musicUuid);

    Page<MusicDTO> getAllMusicByLatest(Pageable pageable, String loggedInUserEmail);

    Page<MusicDTO> getAllMusicByFilters(
            String majorGenre, String hashtags, Double minPrice, Double maxPrice,
            Pageable pageable, String sortBy, String loggedInUserEmail, String searchTerm);

    Page<MusicDTO> getMusicByUser(Pageable pageable, String loggedInUserEmail, String searchTerm, String sortBy);
}
