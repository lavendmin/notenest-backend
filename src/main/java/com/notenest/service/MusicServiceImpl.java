package com.notenest.service;

import com.notenest.domain.Composer;
import com.notenest.domain.Music;
import com.notenest.domain.User;
import com.notenest.dto.BidListDTO;
import com.notenest.dto.CreateMusicDTO;
import com.notenest.dto.MusicDTO;
import com.notenest.dto.MusicDetailDTO;
import com.notenest.dto.MusicSummaryDTO;
import com.notenest.dto.UpdateMusicDTO;
import com.notenest.payment.KrwAmounts;
import com.notenest.repository.LikeRepository;
import com.notenest.repository.MusicRepository;
import com.notenest.repository.UserRepository;
import io.jsonwebtoken.io.IOException;
import io.micrometer.common.util.StringUtils;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;


@Service
public class MusicServiceImpl implements MusicService {

    private static final Logger logger = LoggerFactory.getLogger(MusicServiceImpl.class);

    @Autowired
    private MusicRepository musicRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private BidServiceImpl bidService;

    @Autowired
    private ComposerService composerService;

    @Override
    public Music createMusic(CreateMusicDTO createMusicDTO, String loggedInUserEmail) {
        User user = userRepository.findByEmail(loggedInUserEmail);
        if (user == null) {
            throw new IllegalArgumentException("로그인 후 이용 가능합니다.");
        }

        // 필수 입력값 검증
        if (createMusicDTO.getTitle() == null || createMusicDTO.getTitle().isEmpty()) {
            throw new IllegalArgumentException("음악 제목을 입력하세요.");
        }
        if (createMusicDTO.getStartingPrice() == null) {
            throw new IllegalArgumentException("시작 가격을 입력하세요.");
        }
        // 시작가는 결제 가능한 원 단위 범위여야 한다(입찰·결제 허용 범위와 일치, NB2 금액 계약).
        KrwAmounts.requireWonInRange(createMusicDTO.getStartingPrice(), "시작 가격");
        if (createMusicDTO.getImage() == null || createMusicDTO.getImage().length == 0) {
            throw new IllegalArgumentException("음악 이미지를 업로드하세요.");
        }
        if (createMusicDTO.getAudio() == null || createMusicDTO.getAudio().length == 0) {
            throw new IllegalArgumentException("음악 파일을 업로드하세요.");
        }
        if (createMusicDTO.getMajorGenre() == null || createMusicDTO.getMajorGenre().isEmpty()) {
            throw new IllegalArgumentException("메인 장르를 선택하세요.");
        }
        if (createMusicDTO.getMusicPeriod() == null) {
            throw new IllegalArgumentException("경매 기간을 선택하세요.");
        }
        if (createMusicDTO.getShowAllBids() == null) {
            throw new IllegalArgumentException("입찰 공개 여부를 선택하세요.");
        }

        Music music = new Music();
        // createdAt 설정
        music.setCreatedAt(LocalDateTime.now());
        // musicPeriod 값을 설정하여 auctionEndTime을 자동으로 계산
        music.setMusicPeriod(createMusicDTO.getMusicPeriod());
        BeanUtils.copyProperties(createMusicDTO, music);
        // 최고 입찰가는 서버가 초기화한다(클라이언트 입력 무시) — 곡 생성으로 상한 초과 최고가를 심어
        // 입찰 범위 검증을 우회하는 것을 막는다. 실제 최고가는 입찰(createBid)에서만 갱신된다.
        music.setCurrentHighestBid(null);

        try {
            // 작곡가 정보 가져오기
            List<Composer> composers = composerService.getAllComposerInfo();

            // 작곡가 정보를 반복하면서 해당 작곡가가 있는지 확인하고 값을 설정
            boolean composerFound = false;
            for (Composer composer : composers) {
                if (user.getNickname().equals(composer.getComposer())) {
                    music.setPopularComposer(composer.getPopular());
                    music.setSteadyWorkComposer(composer.getSteadyWork());
                    music.setHitSongComposer(composer.getHitSong());
                    composerFound = true;
                    break; // 작곡가를 찾았으므로 루프를 종료합니다.
                }
            }

            // 작곡가 정보가 없는 경우 기본값인 false로 설정
            if (!composerFound) {
                music.setPopularComposer(false);
                music.setSteadyWorkComposer(false);
                music.setHitSongComposer(false);
            }
        } catch (Exception e) {
            // 예외를 다시 던져서 상위로 전파
            logger.error("Failed to read composer info from JSON file", e);
        }

        music.setUser(user);

        return musicRepository.save(music);
    }


    @Override
    public void deleteMusic(UUID musicUuid, String loggedInUserEmail) {
        Music music = musicRepository.findById(musicUuid)
                .orElseThrow(() -> new IllegalArgumentException("해당 음악을 찾을 수 없습니다."));

        if (!music.getUser().getEmail().equals(loggedInUserEmail)) {
            throw new IllegalArgumentException("해당 게시글의 작성자만 삭제할 수 있습니다.");
        }

        musicRepository.delete(music);
    }

    @Override
    public Music updateMusic(UUID musicUuid, UpdateMusicDTO updateMusicDTO, String loggedInUserEmail) {
        Music music = musicRepository.findById(musicUuid)
                .orElseThrow(() -> new EntityNotFoundException("음악이 존재하지 않습니다."));

        if (!music.getUser().getEmail().equals(loggedInUserEmail)) {
            throw new IllegalArgumentException("해당 곡의 작성자만 수정할 수 있습니다.");
        }

        // 필드가 null이 아닌 경우에만 업데이트
        if (updateMusicDTO.getTitle() != null) {
            music.setTitle(updateMusicDTO.getTitle());
        }
        if (updateMusicDTO.getSubtitle() != null) {
            music.setSubtitle(updateMusicDTO.getSubtitle());
        }
        if (updateMusicDTO.getMajorGenre() != null) {
            music.setMajorGenre(updateMusicDTO.getMajorGenre());
        }
        if (updateMusicDTO.getDetails() != null) {
            music.setDetails(updateMusicDTO.getDetails());
        }
        if (updateMusicDTO.getHashtag() != null) {
            music.setHashtag(updateMusicDTO.getHashtag());
        }
        if (updateMusicDTO.getImage() != null) {
            try {
                music.setImage(updateMusicDTO.getImage());
            } catch (IOException e) {
                throw new RuntimeException("이미지 업데이트 중 오류가 발생했습니다.", e);
            }
        }
        if (updateMusicDTO.getShowAllBids() != null) {
            music.setShowAllBids(updateMusicDTO.getShowAllBids());
        }
        return musicRepository.save(music);
    }

    @Override
    public MusicDetailDTO getMusicDetail(UUID musicUuid) {
        Music music = musicRepository.findById(musicUuid)
                .orElseThrow(() -> new EntityNotFoundException("음악이 존재하지 않습니다."));
        Page<BidListDTO> bidListDTOPage = bidService.getAllBidsByMusic(musicUuid, PageRequest.of(0, 10)); // 예시로 페이지 크기 10으로 설정

        return MusicDetailDTO.fromMusic(music, bidListDTOPage);
    }


    // 공개 경매 곡 목록 — 무필터·검색·필터·정렬 전 분기가 이 단일 경로(QueryDSL 프로젝션)를 탄다.
    @Override
    public Page<MusicSummaryDTO> getAllMusicByFilters(
            String majorGenre, String hashtags, Long minPrice, Long maxPrice,
            Pageable pageable, String sortBy, String loggedInUserEmail, String searchTerm) {

        // 사용자 정보 가져오기
        User user = userRepository.findByEmail(loggedInUserEmail);
        if (user == null) {
            throw new IllegalArgumentException("로그인 후 이용 가능합니다.");
        }

        // QueryDSL DTO 프로젝션 — 엔티티(LOB) 대신 목록에 필요한 컬럼만 SELECT (audio 제외, image 포함).
        Page<MusicSummaryDTO> page = musicRepository.searchSummaries(
                majorGenre, hashtags, minPrice, maxPrice, searchTerm, sortBy, pageable);

        // 좋아요 여부 — 페이지의 곡 UUID를 모아 IN 조회 1회로 처리(N+1 제거).
        List<UUID> pageMusicIds = page.getContent().stream()
                .map(MusicSummaryDTO::getMusicUuid)
                .toList();
        Set<UUID> likedMusicIds = pageMusicIds.isEmpty()
                ? Collections.emptySet()
                : new HashSet<>(likeRepository.findLikedMusicIds(user.getUserUUID(), pageMusicIds));
        page.forEach(dto -> dto.setLikedByUser(likedMusicIds.contains(dto.getMusicUuid())));

        return page;
    }



    //마이페이지 - 내 곡 보기
    @Override
    public Page<MusicDTO> getMusicByUser(
            Pageable pageable, String loggedInUserEmail, String searchTerm, String sortBy) {

        // 사용자 정보 가져오기
        User user = userRepository.findByEmail(loggedInUserEmail);
        if (user == null) {
            throw new IllegalArgumentException("유저를 찾을 수 없습니다.");
        }

        // 검색 조건 추가
        Specification<Music> spec = Specification.where((root, query, cb) -> cb.equal(root.get("user"), user));

        if (StringUtils.isNotBlank(searchTerm)) {
            String searchPattern = "%" + searchTerm.trim() + "%";
            spec = spec.and((root, query, cb) ->
                    cb.or(
                            cb.like(root.get("title"), searchPattern),
                            cb.like(root.get("subtitle"), searchPattern),
                            cb.like(root.get("majorGenre"), searchPattern),
                            cb.like(root.get("hashtag"), searchPattern)
                    )
            );
        }

        // 정렬 조건 추가
        if ("price".equals(sortBy)) {
            pageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.unsorted());

            // 가격 정렬 (currentHighestBid가 null이면 startingPrice로 대체)
            spec = spec.and((root, query, cb) -> {
                query.orderBy(
                        cb.desc(cb.coalesce(root.get("currentHighestBid"), root.get("startingPrice")))
                );
                return query.getRestriction();
            });
        } else if ("like".equals(sortBy)) {
            Sort sort = Sort.by(Sort.Direction.DESC, "likeCount");
            pageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
        } else {
            Sort sort = Sort.by(Sort.Direction.DESC, "createdAt"); // 기본 정렬은 최신 순으로
            pageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
        }

        // 음악 목록 가져오기
        Page<Music> musicPage = musicRepository.findAll(spec, pageable);

        // MusicDTO 리스트 초기화
        List<MusicDTO> musicDTOList = new ArrayList<>();

        // Music 엔티티를 MusicDTO로 변환하고 좋아요 여부 설정
        for (Music music : musicPage.getContent()) {
            boolean likedByUser = likeRepository.countByUserIdAndMusicId(user.getUserUUID(), music.getMusicUuid()) > 0;
            musicDTOList.add(MusicDTO.fromMusic(music, likedByUser));
        }

        // MusicDTO 리스트와 페이지 정보를 사용하여 새로운 페이지 생성 및 반환
        return new PageImpl<>(musicDTOList, pageable, musicPage.getTotalElements());
    }
}
