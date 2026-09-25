package com.notenest.controller;

import com.notenest.dto.CreateMusicDTO;
import com.notenest.dto.MusicDTO;
import com.notenest.dto.MusicDetailDTO;
import com.notenest.dto.MusicSummaryDTO;
import com.notenest.dto.UpdateMusicDTO;
import com.notenest.service.LikeMusicService;
import com.notenest.service.MusicService;
import com.notenest.storage.InvalidMediaException;
import com.notenest.storage.ObjectStorageException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

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
    // 파트: image(커버), preview(미리듣기, 필수), audio(전체 데모), music(JSON). 파일 누락도 서비스 검증이 400 으로 응답한다.
    public ResponseEntity<?> createMusic(@RequestPart(value = "image", required = false) MultipartFile image,
                                         @RequestPart(value = "preview", required = false) MultipartFile preview,
                                         @RequestPart(value = "audio", required = false) MultipartFile audio,
                                         @RequestPart("music") CreateMusicDTO createMusicDTO) {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        try {
            musicService.createMusic(createMusicDTO, image, preview, audio, loggedInUserEmail);
            // JSON 형식으로 반환
            Map<String, String> response = new HashMap<>();
            response.put("message", "Music creation successful.");
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | InvalidMediaException e) {
            // 입력 검증 실패(필수 값·파일 형식·크기) — 클라이언트가 고칠 수 있는 오류
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (ObjectStorageException e) {
            // 파일 저장소 장애 — 이미 올린 파일은 보상 삭제됐고 곡은 저장되지 않았다
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "파일 저장소 오류로 곡을 등록하지 못했습니다. 잠시 후 다시 시도해 주세요."));
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
        } catch (IllegalStateException e) {
            // 첫 입찰 발생 후에는 삭제할 수 없다 — 입찰·낙찰 기록이 곡과 함께 사라지지 않게 한다.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to delete music.");
        }
    }

    // 곡 수정하기 — 파트: image(커버)·preview(미리듣기)·audio(전체 데모)는 보낸 것만 교체, music(JSON)은 메타데이터
    @PutMapping("/{musicUuid}")
    public ResponseEntity<?> updateMusic(@RequestPart(value = "image", required = false) MultipartFile image,
                                         @RequestPart(value = "preview", required = false) MultipartFile preview,
                                         @RequestPart(value = "audio", required = false) MultipartFile audio,
                                         @PathVariable UUID musicUuid,
                                         @RequestPart("music") UpdateMusicDTO updateMusicDTO) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        try {
            musicService.updateMusic(musicUuid, updateMusicDTO, image, preview, audio, loggedInUserEmail);

            // 엔티티를 그대로 직렬화하면 음원 바이트와 작성자 개인정보(User)까지 응답에 실린다 → 상세 DTO로 응답한다.
            return ResponseEntity.ok(musicService.getMusicDetail(musicUuid));
        } catch (IllegalStateException e) {
            // 첫 입찰 발생 후 전체 데모 교체 — 검토한 곡과 거래 대상이 달라지지 않게 거부한다
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (IllegalArgumentException | InvalidMediaException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (ObjectStorageException e) {
            // 새로 올린 파일은 보상 삭제됐고 기존 파일·DB 는 그대로다
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("파일 저장소 오류로 곡을 수정하지 못했습니다.");
        } catch (DataAccessException e) {
            // DB 전환 실패 — 새로 올린 파일은 보상 삭제됐고 기존 파일·DB 는 그대로다
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to update music.");
        }
    }


    @GetMapping("/{musicUuid}")
    public ResponseEntity<MusicDetailDTO> getMusicDetail(@PathVariable UUID musicUuid) {
        MusicDetailDTO musicDetailDTO = musicService.getMusicDetail(musicUuid);
        return ResponseEntity.ok(musicDetailDTO);
    }

    @GetMapping("/filter")
    public ResponseEntity<Page<MusicSummaryDTO>> getAllMusic(
            @RequestParam(required = false) String majorGenre,
            @RequestParam(required = false) String hashtag,
            @RequestParam(required = false) Long minPrice,
            @RequestParam(required = false) Long maxPrice,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "latest") String sortBy,
            @RequestParam(required = false) String searchTerm) {
        try {
            // 목록은 비로그인에게도 공개한다. 익명 인증 토큰도 isAuthenticated()=true 라서 타입으로 구분한다.
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            boolean loggedIn = authentication != null && !(authentication instanceof AnonymousAuthenticationToken);
            String loggedInUserEmail = loggedIn ? authentication.getName() : null;

            Pageable pageable = PageRequest.of(page, size);

            // 필터·검색 유무와 관계없이 단일 조회 경로(QueryDSL 프로젝션)를 탄다.
            // 조건이 없으면 sortBy 만 적용된다 — sortBy 단독 요청(예: ?sortBy=price)도 정렬이 반영된다.
            Page<MusicSummaryDTO> musicDTOPage = musicService.getAllMusicByFilters(
                    majorGenre, hashtag, minPrice, maxPrice, pageable, sortBy, loggedInUserEmail, searchTerm);

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
