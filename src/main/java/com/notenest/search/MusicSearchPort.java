package com.notenest.search;

import com.notenest.dto.MusicSummaryDTO;
import com.notenest.repository.MusicListCondition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * [NB5] 검색어가 있는 공개 목록 조회. 검색어가 없는 목록은 이 포트를 거치지 않고 기존 QueryDSL 경로를 쓴다.
 *
 * sortBy: null·"relevance" = 관련도순(기본), "latest"·"price"·"like" = 명시 정렬, 그 밖의 값 = 최신순(기존 목록과 같은 처리).
 * 반환 DTO 에는 커버 객체 키만 있다 — URL 과 좋아요 여부는 호출한 서비스가 채운다.
 *
 * @throws SearchUnavailableException 검색 엔진에 닿지 못하거나 색인이 없을 때(503)
 * @throws InvalidSearchRequestException 검색이 지원하지 않는 요청일 때(400, 예: 깊은 페이지)
 */
public interface MusicSearchPort {

    Page<MusicSummaryDTO> search(MusicListCondition condition, String sortBy, Pageable pageable);
}
