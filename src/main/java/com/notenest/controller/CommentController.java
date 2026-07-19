package com.notenest.controller;

import com.notenest.dto.CommentDTO;
import com.notenest.service.CommentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/board/{boardUuid}/comment")
public class CommentController {
    private final CommentService commentService;

    // 댓글 작성
    @PostMapping("/create")
    public ResponseEntity<?> createComment(@RequestBody CommentDTO commentDTO, @PathVariable UUID boardUuid) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        Map<String, String> response = new HashMap<>();

        try {
            CommentDTO createComment = commentService.createComment(commentDTO, boardUuid, loggedInUserEmail);
            return ResponseEntity.ok(createComment);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    // 댓글 수정
    @PutMapping("/{commentUuid}")
    public ResponseEntity<?> updateComment(@RequestBody CommentDTO commentDTO, @PathVariable UUID commentUuid) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        Map<String, String> response = new HashMap<>();

        try {
            CommentDTO updatedComment = commentService.updateComment(commentDTO, commentUuid, loggedInUserEmail);
            return ResponseEntity.ok(updatedComment);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    // 댓글 삭제
    @DeleteMapping("/{commentUuid}")
    public ResponseEntity<?> deleteComment(@PathVariable UUID commentUuid) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        Map<String, String> response = new HashMap<>();

        try {
            commentService.deleteComment(commentUuid, loggedInUserEmail);
            response.put("message", "댓글이 삭제되었습니다.");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }
}
