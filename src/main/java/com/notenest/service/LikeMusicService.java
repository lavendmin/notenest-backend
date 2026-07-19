package com.notenest.service;

import com.notenest.domain.Likes;
import com.notenest.domain.Music;
import com.notenest.domain.User;
import com.notenest.dto.MusicListDTO;
import com.notenest.repository.LikeRepository;
import com.notenest.repository.MusicRepository;
import com.notenest.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class LikeMusicService {
    private final LikeRepository likeRepository;
    private final UserRepository userRepository;
    private  final MusicRepository musicRepository;

    public LikeMusicService(LikeRepository likeRepository, UserRepository userRepository, MusicRepository musicRepository, MusicService musicService) {
        this.likeRepository = likeRepository;
        this.userRepository = userRepository;
        this.musicRepository = musicRepository;
    }

    // 로그인한 유저 가져오기
    private User getLoggedInUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalArgumentException("로그인 후 이용 가능합니다.");
        }

        String loggedInUserEmail = authentication.getName();
        User user = userRepository.findByEmail(loggedInUserEmail);
        if (user == null) {
            throw new IllegalArgumentException("사용자를 찾을 수 없습니다.");
        }
        return user;
    }

    /**
     * 찜하기 or 찜 취소
     */
    @Transactional
    public String toggleLikeMusic(UUID musicUuid) {
        User user = getLoggedInUser();

        Music music = musicRepository.findById(musicUuid)
                .orElseThrow(() -> new IllegalArgumentException("해당 음악을 찾을 수 없습니다."));

        if (music.getUser().equals(user)) {
            throw new IllegalArgumentException("본인이 등록한 곡은 찜할 수 없습니다.");
        }

        Likes existingLike = likeRepository.findByUserAndMusic(user, music);

        if (existingLike == null) {
            // 찜하기
            Likes like = new Likes();
            like.setUser(user);
            like.setMusic(music);
            likeRepository.save(like);

            music.setLikeCount(music.getLikeCount() + 1);
            musicRepository.save(music);
            return "찜하기 되었습니다.";
        } else {
            // 찜 취소
            likeRepository.delete(existingLike);

            if (music.getLikeCount() > 0) {
                music.setLikeCount(music.getLikeCount() - 1);
                musicRepository.save(music);
            }
            return "찜이 취소되었습니다.";
        }
    }

    private List<Music> getAllLikedMusic() {
        User user = getLoggedInUser();
        List<Likes> likedMusic = likeRepository.findByUser(user);
        return likedMusic.stream().map(Likes::getMusic).toList();
    }

    private List<MusicListDTO> convertToDTO(List<Music> musicList) {
        return musicList.stream()
                .map(MusicListDTO::fromMusic)
                .collect(Collectors.toList());
    }

    /**
     * 마이페이지 찜 목록 가져오기
     * @param sortBy
     * @param searchTerm
     * @param pageable
     * @return
     */
    public Page<MusicListDTO> getLikedMusic(String sortBy, String searchTerm, Pageable pageable) {
        List<Music> allLikedMusic = getAllLikedMusic();

        // 검색 기능
        if (searchTerm != null && !searchTerm.isEmpty()) {
            String lowerCaseSearchTerm = searchTerm.toLowerCase();
            allLikedMusic = allLikedMusic.stream()
                    .filter(music -> music.getTitle().toLowerCase().contains(lowerCaseSearchTerm) ||
                            music.getUser().getNickname().toLowerCase().contains(lowerCaseSearchTerm))
                    .toList();
        }

        List<MusicListDTO> musicListDTOS = convertToDTO(allLikedMusic);

        switch (sortBy) {
            case "popular":
                musicListDTOS = sortByPopular(musicListDTOS);
                break;
            case "price":
                musicListDTOS = sortByPrice(musicListDTOS);
                break;
            case "latest":
            default:
                musicListDTOS = sortByLatest(musicListDTOS);
                break;
        }
        return getPagedMusic(musicListDTOS, pageable);
    }

    // 최신순 정렬
    public List<MusicListDTO> sortByLatest(List<MusicListDTO> musicListDTOS) {
        return musicListDTOS.stream()
                .sorted(Comparator.comparing(MusicListDTO::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }

    // 인기순 정렬
    public List<MusicListDTO> sortByPopular(List<MusicListDTO> musicListDTOS) {
        return musicListDTOS.stream()
                .sorted(Comparator.comparingInt(MusicListDTO::getLikeCount).reversed())
                .collect(Collectors.toList());
    }

    // 가격순 정렬
    public List<MusicListDTO> sortByPrice(List<MusicListDTO> musicListDTOS) {
        return musicListDTOS.stream()
                .sorted(Comparator.comparingDouble(dto -> {
                    Double currentHighestBid = dto.getCurrentHighestBid();
                    return currentHighestBid != null ? -currentHighestBid : -dto.getStartingPrice();
                }))
                .collect(Collectors.toList());
    }

    // 정렬 및 페이징 적용
    public Page<MusicListDTO> getPagedMusic(List<MusicListDTO> musicListDTOS, Pageable pageable) {
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), musicListDTOS.size());
        List<MusicListDTO> pagedMusicDTOS = musicListDTOS.subList(start, end);
        return new PageImpl<>(pagedMusicDTOS, pageable, musicListDTOS.size());
    }

}
