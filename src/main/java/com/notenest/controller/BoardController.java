package com.notenest.controller;

import com.notenest.dto.BoardDTO;
import com.notenest.dto.BoardListDTO;
import com.notenest.dto.BoardResponseDTO;
import com.notenest.service.BoardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/music/{musicUuid}/board")
public class BoardController {
    private final BoardService boardService;

    // 게시글 작성
    @PostMapping("/create")
    public ResponseEntity<?> createPost(@RequestBody BoardDTO boardDTO, @PathVariable UUID musicUuid) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        try {
            BoardDTO createdPost = boardService.createPost(boardDTO, musicUuid, loggedInUserEmail);
            System.out.println(loggedInUserEmail);
            return ResponseEntity.ok(createdPost);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    // 게시글 수정
    @PutMapping("/{boardUuid}")
    public ResponseEntity<?> updatePost(@RequestBody BoardDTO boardDTO, @PathVariable UUID boardUuid) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        try {
            BoardDTO updatedPost = boardService.updatePost(boardUuid, boardDTO, loggedInUserEmail);
            return ResponseEntity.ok(updatedPost);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    // 게시글 삭제
    @DeleteMapping("/{boardUuid}")
    public ResponseEntity<?> deletePost(@PathVariable UUID boardUuid){
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        try {
            boardService.deletePost(boardUuid, loggedInUserEmail);
            return ResponseEntity.ok("게시글이 삭제되었습니다.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    // 게시글 목록
    @GetMapping("/")
    public ResponseEntity<List<BoardListDTO>> getAllPosts(@PathVariable UUID musicUuid) {
        List<BoardListDTO> postsList = boardService.getAllPosts(musicUuid);
        return ResponseEntity.ok(postsList);
    }

    // 특정 게시글 조회 + 댓글 목록 포함
    @GetMapping("/{boardUuid}")
    public ResponseEntity<?> getPostWithComments(@PathVariable UUID boardUuid) {
        try {
            boardService.increaseHits(boardUuid);
            BoardResponseDTO post = boardService.getPostWithComments(boardUuid);
            return ResponseEntity.ok(post);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }
}
