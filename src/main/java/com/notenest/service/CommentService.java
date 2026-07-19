package com.notenest.service;

import com.notenest.domain.Board;
import com.notenest.domain.Comments;
import com.notenest.domain.User;
import com.notenest.dto.CommentDTO;
import com.notenest.repository.BoardRepository;
import com.notenest.repository.CommentsRepository;
import com.notenest.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CommentService {
    private final CommentsRepository commentsRepository;
    private final BoardRepository boardRepository;
    private final UserRepository userRepository;

    /**
     * 댓글 작성(생성, 저장)
     * @param commentDTO
     * @param boardUuid
     * @param loggedInUserEmail
     * @return
     */
    @Transactional
    public CommentDTO createComment(CommentDTO commentDTO, UUID boardUuid, String loggedInUserEmail) {

        User user = userRepository.findByEmail(loggedInUserEmail);
        if (user == null) {
            throw new IllegalArgumentException("로그인한 사용자를 찾을 수 없습니다.");
        }

        Board board = boardRepository.findById(boardUuid)
                .orElseThrow(() -> new IllegalArgumentException("해당 게시글을 찾을 수 없습니다."));

        if (commentDTO.getContent() == null || commentDTO.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("댓글을 작성해주세요.");
        }

        Comments comment = new Comments();

        comment.setBoard(board);
        comment.setUser(user);
        // 댓글 작성자가 해당 곡 작곡가인지
        if (board.getMusic().getUser().equals(user)) {
            comment.setComposer(true);
        } else {
            comment.setComposer(false);
        }

        comment.setContent(commentDTO.getContent());

        commentsRepository.save(comment);

        return new CommentDTO(
                comment.getCommentUuid(),
                user.getNickname(),
                comment.isComposer(),
                comment.getContent(),
                comment.getCreatedTime(),
                null
        );

    }

    /**
     * 댓글 수정
     * @param commentDTO
     * @param commentUuid
     * @param loggedInUserEmail
     * @return
     */
    @Transactional
    public CommentDTO updateComment(CommentDTO commentDTO, UUID commentUuid, String loggedInUserEmail) {
        Comments comment = commentsRepository.findById(commentUuid)
                .orElseThrow(() -> new IllegalArgumentException("해당 댓글을 찾을 수 없습니다."));

        if (!comment.getUser().getEmail().equals(loggedInUserEmail)) {
            throw new IllegalArgumentException("해당 댓글의 작성자만 수정할 수 있습니다.");
        }

        comment.setContent(commentDTO.getContent());

        Comments updatedComment = commentsRepository.save(comment);

        return new CommentDTO(
                updatedComment.getCommentUuid(),
                updatedComment.getUser().getNickname(),
                updatedComment.isComposer(),
                updatedComment.getContent(),
                updatedComment.getCreatedTime(),
                updatedComment.getUpdatedTime()
        );
    }

    /**
     * 댓글 삭제
     * @param commentUuid
     * @param loggedInUserEmail
     */
    @Transactional
    public void deleteComment(UUID commentUuid, String loggedInUserEmail) {
        Comments comment = commentsRepository.findById(commentUuid)
                .orElseThrow(() -> new IllegalArgumentException("해당 댓글을 찾을 수 없습니다."));

        if (!comment.getUser().getEmail().equals(loggedInUserEmail)) {
            throw new IllegalArgumentException("해당 댓글의 작성자만 삭제할 수 있습니다.");
        }

        commentsRepository.delete(comment);
    }

}
