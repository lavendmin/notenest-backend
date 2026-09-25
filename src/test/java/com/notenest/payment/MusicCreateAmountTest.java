package com.notenest.payment;

import com.notenest.domain.Composer;
import com.notenest.domain.Music;
import com.notenest.domain.User;
import com.notenest.dto.CreateMusicDTO;
import com.notenest.repository.LikeRepository;
import com.notenest.repository.MusicRepository;
import com.notenest.repository.UserRepository;
import com.notenest.service.BidServiceImpl;
import com.notenest.service.ComposerService;
import com.notenest.service.MusicServiceImpl;
import com.notenest.storage.MediaUploadValidator;
import com.notenest.storage.MusicMediaStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 곡 생성 시 금액 필드 처리 검증 (P2 우회 차단).
 *
 * <p>생성 DTO 에 노출된 currentHighestBid 로 상한 초과 최고가를 심어 입찰 범위 검증을 우회하는
 * 경로를 막았는지 고정한다 — 최고 입찰가는 서버가 null 로 초기화해야 한다.
 */
@ExtendWith(MockitoExtension.class)
class MusicCreateAmountTest {

    @Mock private MusicRepository musicRepository;
    @Mock private UserRepository userRepository;
    @Mock private LikeRepository likeRepository;
    @Mock private BidServiceImpl bidService;
    @Mock private ComposerService composerService;
    @Spy private MediaUploadValidator mediaUploadValidator = new MediaUploadValidator();
    @Mock private MusicMediaStorage musicMediaStorage;

    @InjectMocks private MusicServiceImpl musicService;

    private static final String COMPOSER = "composer@test.local";

    private CreateMusicDTO baseDto() {
        CreateMusicDTO dto = new CreateMusicDTO();
        dto.setTitle("track");
        dto.setStartingPrice(10000L);
        dto.setMajorGenre("POP");
        dto.setMusicPeriod(7);
        dto.setShowAllBids(true);
        return dto;
    }

    // 시그니처가 맞는 최소 파일 — 금액 계약만 보는 테스트라 저장은 목(MusicMediaStorage)이 대신한다.
    private static final MockMultipartFile COVER = new MockMultipartFile("image", "c.png", "image/png",
            new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
    private static final MockMultipartFile PREVIEW = new MockMultipartFile("preview", "p.mp3", "audio/mpeg",
            new byte[]{'I', 'D', '3', 4, 0, 0});
    private static final MockMultipartFile FULL_DEMO = new MockMultipartFile("audio", "f.mp3", "audio/mpeg",
            new byte[]{'I', 'D', '3', 4, 0, 0});

    @Test
    @DisplayName("곡 생성 시 클라이언트가 보낸 currentHighestBid(상한 초과)는 무시되고 서버가 null 로 초기화한다")
    void create_ignoresClientCurrentHighestBid() {
        when(userRepository.findByEmail(COMPOSER)).thenReturn(new User());
        when(composerService.getAllComposerInfo()).thenReturn(Collections.<Composer>emptyList());
        when(musicRepository.save(any(Music.class))).thenAnswer(inv -> inv.getArgument(0));
        when(musicMediaStorage.uploadAll(any(), any())).thenReturn(Map.of());

        CreateMusicDTO dto = baseDto();
        dto.setCurrentHighestBid(10_000_000_001L); // 상한 초과 최고가 주입 시도

        musicService.createMusic(dto, COVER, PREVIEW, FULL_DEMO, COMPOSER);

        ArgumentCaptor<Music> captor = ArgumentCaptor.forClass(Music.class);
        verify(musicRepository).save(captor.capture());
        assertThat(captor.getValue().getCurrentHighestBid()).isNull();
        assertThat(captor.getValue().getStartingPrice()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("시작가가 상한을 넘으면 곡 생성이 거부된다 (저장 없음)")
    void create_rejectsStartingPriceOverCap() {
        when(userRepository.findByEmail(COMPOSER)).thenReturn(new User());

        CreateMusicDTO dto = baseDto();
        dto.setStartingPrice(com.notenest.payment.KrwAmounts.MAX_WON + 1);

        assertThatThrownBy(() -> musicService.createMusic(dto, COVER, PREVIEW, FULL_DEMO, COMPOSER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("허용 범위");

        verify(musicRepository, never()).save(any());
        verify(musicMediaStorage, never()).uploadAll(any(), any()); // 금액 검증 실패 시 파일도 올리지 않는다
    }
}
