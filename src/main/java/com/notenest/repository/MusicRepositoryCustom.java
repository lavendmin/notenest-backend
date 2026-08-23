package com.notenest.repository;

import com.notenest.dto.MusicSummaryDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 공개 경매 곡 목록의 동적 검색·필터·정렬을 QueryDSL 로 처리하는 커스텀 리포지토리.
 * 엔티티(LOB 포함)를 반환하지 않고 목록 전용 DTO 프로젝션(audio 제외, image 포함)을 반환한다.
 */
public interface MusicRepositoryCustom {

    Page<MusicSummaryDTO> searchSummaries(
            String majorGenre, String hashtags, Double minPrice, Double maxPrice,
            String searchTerm, String sortBy, Pageable pageable);
}
