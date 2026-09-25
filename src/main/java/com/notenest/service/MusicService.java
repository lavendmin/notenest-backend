package com.notenest.service;

import com.notenest.domain.Music;
import com.notenest.dto.CreateMusicDTO;
import org.springframework.web.multipart.MultipartFile;
import com.notenest.dto.MusicDTO;
import com.notenest.dto.MusicDetailDTO;
import com.notenest.dto.MusicSummaryDTO;
import com.notenest.dto.UpdateMusicDTO;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface MusicService {

    // 커버·미리듣기·전체 데모 파일은 객체 저장소에 올리고 DB 에는 객체 키만 저장한다(미리듣기 필수).
    Music createMusic(CreateMusicDTO createMusicDTO, MultipartFile cover, MultipartFile preview,
                      MultipartFile fullDemo, String loggedInUserEmail);

    void deleteMusic(UUID musicUuid, String loggedInUserEmail);

    // 파일은 보낸 것만 교체한다. 새 키로 올리고 DB 전환 성공 후 옛 객체를 지운다. 전체 데모는 첫 입찰 전까지만 교체할 수 있다.
    Music updateMusic(UUID musicUuid, UpdateMusicDTO updateMusicDTO, MultipartFile cover, MultipartFile preview,
                      MultipartFile fullDemo, String loggedInUserEmail);

    MusicDetailDTO getMusicDetail(UUID musicUuid);

    Page<MusicSummaryDTO> getAllMusicByFilters(
            String majorGenre, String hashtags, Long minPrice, Long maxPrice,
            Pageable pageable, String sortBy, String loggedInUserEmail, String searchTerm);

    Page<MusicDTO> getMusicByUser(Pageable pageable, String loggedInUserEmail, String searchTerm, String sortBy);
}
