package com.notenest.search;

import com.notenest.domain.MusicalKey;
import com.notenest.dto.MusicSummaryDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MusicSearchDocumentTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static MusicSearchSource source(Long bid, LocalDateTime endTime) {
        return new MusicSearchSource(ID, LocalDateTime.of(2026, 9, 1, 0, 0), "봄비", "부제", null, "#sad", "balad", "윤슬", 0,
                300_000L, bid, endTime, 12, 76, MusicalKey.E_FLAT_MAJOR, "music/" + ID + "/cover/c");
    }

    @Test
    @DisplayName("가격 필터 값은 최고 입찰가, 없으면 시작가 — 목록 계약과 같은 기준")
    void effectivePrice() {
        assertThat(MusicSearchDocument.from(source(420_000L, null)).price()).isEqualTo(420_000L);
        assertThat(MusicSearchDocument.from(source(null, null)).price()).isEqualTo(300_000L);
    }

    @Test
    @DisplayName("시각은 초까지 항상 쓰는 ISO-8601 — 정각도 초가 빠지지 않는다. 키는 enum 이름")
    void formatsForIndex() {
        MusicSearchDocument doc = MusicSearchDocument.from(source(null, LocalDateTime.of(2026, 10, 1, 12, 0)));
        assertThat(doc.createdAt()).isEqualTo("2026-09-01T00:00:00");
        assertThat(doc.auctionEndTime()).isEqualTo("2026-10-01T12:00:00");
        assertThat(doc.musicalKey()).isEqualTo("E_FLAT_MAJOR");
        assertThat(doc.musicId()).isEqualTo(ID.toString());
    }

    @Test
    @DisplayName("목록 DTO 로 되돌릴 때 필드가 그대로이고 마감 시각의 소수 초도 보존된다(커버는 키만, URL 은 서비스가 발급)")
    void roundTripsToSummary() {
        LocalDateTime end = LocalDateTime.of(2026, 10, 1, 12, 0, 5, 123_456_000);
        MusicSummaryDTO dto = MusicSearchDocument.from(source(420_000L, end)).toSummary();
        assertThat(dto.getMusicUuid()).isEqualTo(ID);
        assertThat(dto.getTitle()).isEqualTo("봄비");
        assertThat(dto.getUserNickName()).isEqualTo("윤슬");
        assertThat(dto.getStartingPrice()).isEqualTo(300_000L);
        assertThat(dto.getCurrentHighestBid()).isEqualTo(420_000L);
        assertThat(dto.getAuctionEndTime()).isEqualTo(end);
        assertThat(dto.getLikeCount()).isEqualTo(12);
        assertThat(dto.getCoverObjectKey()).isEqualTo("music/" + ID + "/cover/c");
        assertThat(dto.getCoverUrl()).isNull();
    }
}
