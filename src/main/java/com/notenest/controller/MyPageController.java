package com.notenest.controller;

import com.notenest.domain.Music;
import com.notenest.dto.CompletedBidDTO;
import com.notenest.dto.MusicListDTO;
import com.notenest.dto.MyBidListDTO;
import com.notenest.dto.PendingBidDTO;
import com.notenest.dto.UserMyPageDTO;
import com.notenest.dto.UserUpdatePasswordDTO;
import com.notenest.repository.MusicRepository;
import com.notenest.service.BidService;
import com.notenest.service.DownloadService;
import com.notenest.service.LikeMusicService;
import com.notenest.service.UserService;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


@RequestMapping("/api/mypage")
@RestController
public class MyPageController {
    private final LikeMusicService likeMusicService;
    private final UserService userService;
    private final BidService bidService;
    private final DownloadService downloadService;
    private final MusicRepository musicRepository;

    public MyPageController(LikeMusicService likeMusicService, UserService userService, BidService bidService, DownloadService downloadService, MusicRepository musicRepository) {
        this.likeMusicService = likeMusicService;
        this.userService = userService;
        this.bidService = bidService;
        this.downloadService = downloadService;
        this.musicRepository = musicRepository;
    }

    // 마이페이지 내 정보 조회
    @GetMapping("/myInfo")
    public ResponseEntity<UserMyPageDTO> getMyPage() {
        return ResponseEntity.ok(userService.getMyPage());
    }


    // 마이페이지 비밀번호 수정
    @PutMapping("/updatePassword")
    public ResponseEntity<Map<String, String>> updatePassword(@RequestBody UserUpdatePasswordDTO updatePasswordDTO) {
        try {
            userService.updatePassword(updatePasswordDTO);

            // JSON 형태의 응답 반환
            Map<String, String> response = new HashMap<>();
            response.put("message", "비밀번호가 업데이트되었습니다.");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            // JSON 형태의 응답 반환
            Map<String, String> response = new HashMap<>();
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }


    // 마이페이지 찜 목록 - 정렬 & 검색
    @GetMapping("/likes")
    public ResponseEntity<?> getMyLikedMusic(@RequestParam(required = false, defaultValue = "latest") String sortBy,
                                             @RequestParam(required = false) String searchTerm,
                                             Pageable pageable) {
        try {
            Page<MusicListDTO> likedMusicDTOs = likeMusicService.getLikedMusic(sortBy, searchTerm, pageable);
            return ResponseEntity.ok(likedMusicDTOs);
        } catch (IllegalArgumentException e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        } catch (Exception e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Internal Server Error");
            errorResponse.put("details", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    // 마이페이지 입찰 내역
    @GetMapping("/my-bids")
    public ResponseEntity<Page<MyBidListDTO>> getMyBids(@RequestParam(required = false) String fromDate,
                                                        @RequestParam(required = false) String toDate,
                                                        @RequestParam(required = false) String searchTerm,
                                                        @RequestParam(required = false, defaultValue = "latest") String sortBy,
                                                        Pageable pageable) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        // 시간 빼고 날짜만 String으로 받은 걸 LocalDateTime으로 변환
        LocalDateTime from = (fromDate != null && !fromDate.isEmpty()) ? LocalDate.parse(fromDate).atStartOfDay() : null;
        LocalDateTime to = (toDate != null && !toDate.isEmpty()) ? LocalDate.parse(toDate).atTime(LocalTime.MAX) : null;

        Page<MyBidListDTO> myBidListDTOS = bidService.getUserBids(loggedInUserEmail, from, to, searchTerm, sortBy, pageable);
        return ResponseEntity.ok(myBidListDTOS);
    }

    // 마이페이지 낙찰내역 - 결제 대기
    @GetMapping("/bidSuccess/pendingBids")
    public ResponseEntity<Page<PendingBidDTO>> getPendingBids(@RequestParam(required = false) String fromDate,
                                                              @RequestParam(required = false) String toDate,
                                                              @RequestParam(required = false) String searchTerm,
                                                              @RequestParam(required = false, defaultValue = "latest") String sortBy,
                                                              Pageable pageable) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        // 시간 빼고 날짜만 String으로 받은 걸 LocalDateTime으로 변환. Optional -> 빈 문자열, null 다 처리되도록
        LocalDateTime from = (fromDate != null && !fromDate.isEmpty()) ? LocalDate.parse(fromDate).atStartOfDay() : null;
        LocalDateTime to = (toDate != null && !toDate.isEmpty()) ? LocalDate.parse(toDate).atTime(LocalTime.MAX) : null;

        Page<PendingBidDTO> pendingBidDTOS = bidService.getPendingBids(loggedInUserEmail,from, to, searchTerm, sortBy, pageable);
        return ResponseEntity.ok(pendingBidDTOS);
    }

    // 마이페이지 낙찰내역 - 결제 완료
    @GetMapping("/bidSuccess/completedBids")
    public ResponseEntity<Page<CompletedBidDTO>> getCompletedBids(@RequestParam(required = false) String fromDate,
                                                                  @RequestParam(required = false) String toDate,
                                                                  @RequestParam(required = false) String searchTerm,
                                                                  @RequestParam(required = false, defaultValue = "latest") String sortBy,
                                                                  Pageable pageable) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        // 시간 빼고 날짜만 String으로 받은 걸 LocalDateTime으로 변환. Optional -> 빈 문자열, null 다 처리되도록
        LocalDateTime from = (fromDate != null && !fromDate.isEmpty()) ? LocalDate.parse(fromDate).atStartOfDay() : null;
        LocalDateTime to = (toDate != null && !toDate.isEmpty()) ? LocalDate.parse(toDate).atTime(LocalTime.MAX) : null;

        Page<CompletedBidDTO> completedBidDTOS = bidService.getCompletedBids(loggedInUserEmail,from, to, searchTerm, sortBy, pageable);
        return ResponseEntity.ok(completedBidDTOS);
    }

    // 음원 다운로드
    @GetMapping("/download/{musicUuid}")
    public ResponseEntity<byte[]> downloadMusic(@PathVariable UUID musicUuid) throws UnsupportedEncodingException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        byte[] audioData = downloadService.downloadMusic(musicUuid,loggedInUserEmail);

        Music music = musicRepository.findById(musicUuid)
                .orElseThrow(() -> new IllegalArgumentException("Invalid music UUID: " + musicUuid));

        String filename = music.getTitle();
        if (music.getSubtitle() != null && !music.getSubtitle().isEmpty()) {
            filename += "(" + music.getSubtitle() + ")";
        }
        filename += ".mp3";

        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8.toString());
        String contentDisposition = "attachment; filename=\"" + encodedFilename + "\"";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .body(audioData);
    }

}
