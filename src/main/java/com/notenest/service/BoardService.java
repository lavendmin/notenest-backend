package com.notenest.service;

import com.notenest.domain.Board;
import com.notenest.domain.Music;
import com.notenest.domain.User;
import com.notenest.dto.BoardDTO;
import com.notenest.dto.BoardListDTO;
import com.notenest.dto.BoardResponseDTO;
import com.notenest.dto.CommentDTO;
import com.notenest.repository.BoardRepository;
import com.notenest.repository.MusicRepository;
import com.notenest.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;


@Service
public class BoardService {
    private final BoardRepository boardRepository;
    private final UserRepository userRepository;
    private final MusicRepository musicRepository;

    public BoardService(BoardRepository boardRepository, UserRepository userRepository, MusicRepository musicRepository) {
        this.boardRepository = boardRepository;
        this.userRepository = userRepository;
        this.musicRepository = musicRepository;
    }

    /**
     * 게시판 게시글 작성(저장)
     */
    @Transactional
    public BoardDTO createPost(BoardDTO boardDTO, UUID musicUuid, String loggedInUserEmail) {

        User user = userRepository.findByEmail(loggedInUserEmail);
        if (user == null) {
            throw new IllegalArgumentException("로그인한 사용자를 찾을 수 없습니다.");
        }

        Music music = musicRepository.findById(musicUuid)
                .orElseThrow(() -> new IllegalArgumentException("해당 음악을 찾을 수 없습니다."));

        Board board = new Board();

        board.setMusic(music);
        board.setUser(user);

        board.setTitle(boardDTO.getTitle());
        board.setContent(boardDTO.getContent());

        boardRepository.save(board);

        return new BoardDTO(
                board.getBoardUuid(),
                user.getNickname(),
                board.getTitle(),
                board.getContent(),
                board.getCreatedTime(),
                null
        );
    }

    /**
     * 게시글 수정
     */
    @Transactional
    public BoardDTO updatePost(UUID boardUuid, BoardDTO boardDTO, String loggedInUserEmail) {
        Board board = boardRepository.findById(boardUuid)
                .orElseThrow(() -> new IllegalArgumentException("해당 게시글을 찾을 수 없습니다."));

        if (!board.getUser().getEmail().equals(loggedInUserEmail)) {
            throw new IllegalArgumentException("해당 게시글의 작성자만 수정할 수 있습니다.");
        }

        board.setTitle(boardDTO.getTitle());
        board.setContent(boardDTO.getContent());

        Board updatedBoard = boardRepository.save(board);

        return new BoardDTO(
                updatedBoard.getBoardUuid(),
                updatedBoard.getUser().getNickname(),
                updatedBoard.getTitle(),
                updatedBoard.getContent(),
                updatedBoard.getCreatedTime(),
                updatedBoard.getUpdatedTime()
        );
    }

    /**
     * 게시글 삭제
     */
    @Transactional
    public void deletePost(UUID boardUuid, String loggedInUserEmail) {
        Board board = boardRepository.findById(boardUuid)
                .orElseThrow(() -> new IllegalArgumentException("해당 게시글을 찾을 수 없습니다."));

        if (!board.getUser().getEmail().equals(loggedInUserEmail)) {
            throw new IllegalArgumentException("해당 게시글의 작성자만 삭제할 수 있습니다.");
        }

        boardRepository.delete(board);
    }

    /**
     * 동일한 music_uuid에 대해서 게시글 목록 조회
     */
    public List<BoardListDTO> getAllPosts(UUID musicUuid) {
        Music music = musicRepository.findById(musicUuid)
                .orElseThrow(() -> new IllegalArgumentException("해당 음악을 찾을 수 없습니다."));

        List<Board> boards = boardRepository.findAllByMusic(music);
        boards.sort(Comparator.comparing(Board::getCreatedTime)); // 작성일 순으로 정렬

        List<BoardListDTO> boardListDTOS = new ArrayList<>();
        for (int i=0; i<boards.size(); i++) {
            Board board = boards.get(i);
            BoardListDTO boardListDTO = new BoardListDTO(
                    board.getBoardUuid(),
                    i + 1,
                    board.getTitle(),
                    board.getUser().getNickname(),
                    board.getCreatedTime(),
                    board.getHits()
            );
            boardListDTOS.add(boardListDTO);
        }
        return boardListDTOS;
    }


    /**
     * 게시글 상세 보기 with 댓글 목록
     * @param boardUuid
     * @return
     */

    @Transactional(readOnly = true)
    public BoardResponseDTO getPostWithComments(UUID boardUuid) {
        Board board = boardRepository.findById(boardUuid)
                .orElseThrow(() -> new IllegalArgumentException("해당 게시글을 찾을 수 없습니다."));

        board.getComments().size(); // 댓글들을 Lazy 로딩

        List<CommentDTO> comments = board.getComments().stream()
                .map(comment -> new CommentDTO(
                        comment.getCommentUuid(),
                        comment.getUser().getNickname(),
                        comment.isComposer(),
                        comment.getContent(),
                        comment.getCreatedTime(),
                        comment.getUpdatedTime()
                ))
                .collect(Collectors.toList());

        return new BoardResponseDTO(
                board.getBoardUuid(),
                board.getUser().getNickname(),
                board.getTitle(),
                board.getContent(),
                board.getHits(),
                board.getCreatedTime(),
                board.getUpdatedTime(),
                comments
        );
    }

    /**
     * 조회수 증가
     */
    @Transactional
    public void increaseHits(UUID boardUuid) {
        Board board = boardRepository.findById(boardUuid)
                .orElseThrow(() -> new IllegalArgumentException("해당 게시글을 찾을 수 없습니다."));

        board.increaseHits();
        boardRepository.save(board);
    }

}
