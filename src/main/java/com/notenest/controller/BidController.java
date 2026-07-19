package com.notenest.controller;

import com.notenest.domain.Bid;
import com.notenest.domain.User;
import com.notenest.dto.BidListDTO;
import com.notenest.dto.CreateBidDTO;
import com.notenest.service.BidService;
import com.notenest.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
@RestController
@RequestMapping("/api/bid")
public class BidController {

    @Autowired
    private BidService bidService;

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @PostMapping("/create")
    public ResponseEntity<?> createBid(@RequestBody CreateBidDTO createBidDTO) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();
        try {
            // 사용자 정보 가져오기
            User user = userService.findByEmail(loggedInUserEmail);
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not found.");
            }

            // 비밀번호 검증
            if (!passwordEncoder.matches(createBidDTO.getPassword(), user.getPassword())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid password.");
            }

            // 입찰 생성
            Bid createdBid = bidService.createBid(createBidDTO, loggedInUserEmail);
            return ResponseEntity.status(HttpStatus.CREATED).body("Bid creation successful.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            // 예외 로그 추가
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to create bid. " + e.getMessage());
        }
    }




    // 입찰 삭제 엔드포인트
    @DeleteMapping("/{bidUuid}")
    public ResponseEntity<?> deleteBid(@PathVariable UUID bidUuid) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        try {
            bidService.deleteBid(bidUuid, loggedInUserEmail);
            return ResponseEntity.ok("입찰이 삭제되었습니다.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to delete bid.");
        }
    }

    // 특정 음악의 입찰 리스트 조회 엔드포인트
    @GetMapping("/list/{musicUuid}")
    public ResponseEntity<Page<BidListDTO>> getAllBidsByMusic(@PathVariable UUID musicUuid, Pageable pageable) {
        Page<BidListDTO> bids = bidService.getAllBidsByMusic(musicUuid, pageable);
        return new ResponseEntity<>(bids, HttpStatus.OK);
    }

    @GetMapping("/my-bids")
    public ResponseEntity<Page<Bid>> getMyBids(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();
        // 가져온 사용자 엔티티를 이용하여 사용자가 등록한 곡들을 조회합니다.
        Pageable pageable = PageRequest.of(page, size);
        Page<Bid> bidPage = bidService.getBidsByUser(pageable, loggedInUserEmail);
        return ResponseEntity.ok(bidPage);
    }


}
