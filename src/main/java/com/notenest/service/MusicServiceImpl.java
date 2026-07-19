package com.notenest.service;

import com.notenest.domain.Composer;
import com.notenest.domain.Music;
import com.notenest.domain.User;
import com.notenest.dto.BidListDTO;
import com.notenest.dto.CreateMusicDTO;
import com.notenest.dto.MusicDTO;
import com.notenest.dto.MusicDetailDTO;
import com.notenest.dto.UpdateMusicDTO;
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
import java.util.List;
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


    //최신 순으로 정렬
    @Override
    public Page<MusicDTO> getAllMusicByLatest(Pageable pageable, String loggedInUserEmail) {
        // 사용자 정보 가져오기
        User user = userRepository.findByEmail(loggedInUserEmail);
        if (user == null) {
            throw new IllegalArgumentException("로그인 후 이용 가능합니다.");
        }

        // 최신 음악 목록 가져오기
        Page<Music> musicPage = musicRepository.findAllOngoingMusicByOrderByCreatedAtDesc(pageable);

        // MusicDTO 리스트 초기화
        List<MusicDTO> musicDTOList = new ArrayList<>();

        // Music 엔티티를 MusicDTO로 변환하고 좋아요 여부 설정
        for (Music music : musicPage.getContent()) {
            /// 좋아요 여부 확인
            boolean likedByUser = likeRepository.countByUserIdAndMusicId(user.getUserUUID(), music.getMusicUuid()) > 0;
            musicDTOList.add(MusicDTO.fromMusic(music, likedByUser));
        }

        // MusicDTO 리스트와 페이지 정보를 사용하여 새로운 페이지 생성 및 반환
        return new PageImpl<>(musicDTOList, pageable, musicPage.getTotalElements());
    }

    @Override
    public Page<MusicDTO> getAllMusicByFilters(
            String majorGenre, String hashtags, Double minPrice, Double maxPrice,
            Pageable pageable, String sortBy, String loggedInUserEmail, String searchTerm) {

        // 사용자 정보 가져오기
        User user = userRepository.findByEmail(loggedInUserEmail);
        if (user == null) {
            throw new IllegalArgumentException("로그인 후 이용 가능합니다.");
        }

        // 검색 조건 및 필터링 추가
        Specification<Music> spec = Specification.where((root, query, cb) -> {
            Predicate statusPredicate = cb.equal(root.get("status"), 0); // status가 0인 음악만 검색
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(statusPredicate);

            if (StringUtils.isNotBlank(searchTerm)) {
                String searchPattern = "%" + searchTerm.trim() + "%";
                // 조인을 통해 User 엔티티와 연결
                Join<Music, User> userJoin = root.join("user", JoinType.LEFT);

                predicates.add(cb.or(
                        cb.like(root.get("title"), searchPattern),
                        cb.like(root.get("subtitle"), searchPattern),
                        cb.like(root.get("majorGenre"), searchPattern),
                        cb.like(root.get("hashtag"), searchPattern),
                        cb.like(userJoin.get("nickname"), searchPattern)
                ));
            }

            // 장르에 따라 필터링 추가
            if (majorGenre != null && !majorGenre.isEmpty()) {
                predicates.add(cb.equal(root.get("majorGenre"), majorGenre));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        });

        // 해시태그에 따라 필터링 추가
        if (hashtags != null && !hashtags.isEmpty()) {
            String[] hashtagArray = hashtags.split(",");
            for (String hashtag : hashtagArray) {
                spec = spec.and((root, query, cb) -> cb.like(root.get("hashtag"), "%" + hashtag.trim() + "%"));
            }
        }

        // 가격 범위에 따라 필터링 추가 (currentHighestBid가 null일 경우 startingPrice 사용)
        if (minPrice != null && maxPrice != null) {
            spec = spec.and((root, query, cb) -> cb.between(cb.coalesce(root.get("currentHighestBid"), root.get("startingPrice")), minPrice, maxPrice));
        } else if (minPrice != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(cb.coalesce(root.get("currentHighestBid"), root.get("startingPrice")), minPrice));
        } else if (maxPrice != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(cb.coalesce(root.get("currentHighestBid"), root.get("startingPrice")), maxPrice));
        }

        // 정렬 조건 추가
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt"); // 기본 정렬은 최신 순으로

        if ("price".equals(sortBy)) {
            sort = Sort.by(Sort.Order.desc("currentHighestBid").nullsLast())
                    .and(Sort.by(Sort.Order.desc("startingPrice")));
        } else if ("like".equals(sortBy)) {
            sort = Sort.by(Sort.Direction.DESC, "likeCount");
        }

        Pageable sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);


        // 해당 조건에 맞는 음악 목록 가져오기
        Page<Music> musicPage = musicRepository.findAll(spec, sortedPageable);

        // MusicDTO 리스트 초기화
        List<MusicDTO> musicDTOList = new ArrayList<>();

        for (Music music : musicPage.getContent()) {
            // 좋아요 여부 확인
            boolean likedByUser = likeRepository.countByUserIdAndMusicId(user.getUserUUID(), music.getMusicUuid()) > 0;
            musicDTOList.add(MusicDTO.fromMusic(music, likedByUser));
        }

        // MusicDTO 리스트와 페이지 정보를 사용하여 새로운 페이지 생성 및 반환
        return new PageImpl<>(musicDTOList, sortedPageable, musicPage.getTotalElements());
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
