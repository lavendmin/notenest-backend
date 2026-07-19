package com.notenest.controller;

import com.notenest.domain.Music;
import com.notenest.dto.CreateMusicDTO;
import com.notenest.dto.MusicDTO;
import com.notenest.dto.MusicDetailDTO;
import com.notenest.dto.UpdateMusicDTO;
import com.notenest.service.LikeMusicService;
import com.notenest.service.MusicService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/music")
public class MusicController {

    @Autowired
    private MusicService musicService;

    @Autowired
    private LikeMusicService likeMusicService;

    // 곡 생성하기
    @PostMapping("/create")
    public ResponseEntity<?> createMusic(@RequestPart("image") MultipartFile image,
                                         @RequestPart("audio") MultipartFile audio,
                                         @RequestPart("music") CreateMusicDTO createMusicDTO) {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        try {
            createMusicDTO.setImage(image.getBytes());
            createMusicDTO.setAudio(audio.getBytes());

            musicService.createMusic(createMusicDTO, loggedInUserEmail);
            // JSON 형식으로 반환
            Map<String, String> response = new HashMap<>();
            response.put("message", "Music creation successful.");
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            // 에러 메시지를 클라이언트에게 JSON 형식으로 전달
            Map<String, String> response = new HashMap<>();
            response.put("message", "Failed to create music.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }


    // 곡 삭제하기
    @DeleteMapping("/{musicUuid}")
    public ResponseEntity<String> deleteMusic(@PathVariable UUID musicUuid) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        try {
            musicService.deleteMusic(musicUuid, loggedInUserEmail);
            return ResponseEntity.ok("곡이 삭제되었습니다.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to delete music.");
        }
    }

    // 곡 수정하기
    @PutMapping("/{musicUuid}")
    public ResponseEntity<?> updateMusic(@RequestPart(value = "image", required = false) MultipartFile image,
                                         @PathVariable UUID musicUuid,
                                         @RequestPart("music") UpdateMusicDTO updateMusicDTO) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        try {
            if (image != null) {
                updateMusicDTO.setImage(image.getBytes());
            }

            Music updatedMusic = musicService.updateMusic(musicUuid, updateMusicDTO, loggedInUserEmail);

            return ResponseEntity.ok(updatedMusic);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    @GetMapping("/{musicUuid}")
    public ResponseEntity<MusicDetailDTO> getMusicDetail(@PathVariable UUID musicUuid) {
        MusicDetailDTO musicDetailDTO = musicService.getMusicDetail(musicUuid);
        return ResponseEntity.ok(musicDetailDTO);
    }

    @GetMapping("/filter")
    public ResponseEntity<Page<MusicDTO>> getAllMusic(
            @RequestParam(required = false) String majorGenre,
            @RequestParam(required = false) String hashtag,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "latest") String sortBy,
            @RequestParam(required = false) String searchTerm) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String loggedInUserEmail = (authentication != null && authentication.isAuthenticated()) ? authentication.getName() : null;

            Pageable pageable = PageRequest.of(page, size);
            Page<MusicDTO> musicDTOPage;

            // 필터링 조건이 있거나 검색어가 있는 경우
            if ((majorGenre != null && !majorGenre.isEmpty()) ||
                    (hashtag != null && !hashtag.isEmpty()) ||
                    (minPrice != null || maxPrice != null) ||
                    (searchTerm != null && !searchTerm.isEmpty())) {

                musicDTOPage = musicService.getAllMusicByFilters(
                        majorGenre, hashtag, minPrice, maxPrice, pageable, sortBy, loggedInUserEmail, searchTerm);
            } else {
                // 필터링 조건이 없으면 최신순으로 음악 목록을 가져오기
                musicDTOPage = musicService.getAllMusicByLatest(pageable, loggedInUserEmail);
            }

            return ResponseEntity.ok(musicDTOPage);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }


    //마이페이지 - 내 곡 보기
    @GetMapping("/my-music")
    public ResponseEntity<Page<MusicDTO>> getMyMusic(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String searchTerm,
            @RequestParam(defaultValue = "latest") String sortBy) {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        String loggedInUserEmail = authentication.getName();

        // 가져온 사용자 엔티티를 이용하여 사용자가 등록한 곡들을 조회
        Pageable pageable = PageRequest.of(page, size);
        Page<MusicDTO> musicDTOPage = musicService.getMusicByUser(pageable, loggedInUserEmail, searchTerm, sortBy);
        return ResponseEntity.ok(musicDTOPage);
    }

    // 찜하기 or 찜 취소
    @PostMapping("/{musicUuid}/like")
    public ResponseEntity<Map<String, String>> toggleLikeMusic(@PathVariable UUID musicUuid) {
        try {
            String resultMessage = likeMusicService.toggleLikeMusic(musicUuid);

            // JSON 형태의 응답 반환
            Map<String, String> response = new HashMap<>();
            response.put("message", resultMessage);

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, String> response = new HashMap<>();
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            Map<String, String> response = new HashMap<>();
            response.put("error", "찜하기를 처리하는 도중 오류가 발생했습니다.");
            return ResponseEntity.internalServerError().body(response);
        }
    }

}
