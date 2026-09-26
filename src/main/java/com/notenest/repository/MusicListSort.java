package com.notenest.repository;

import org.springframework.data.domain.Sort;

/**
 * 공개 목록 응답의 Page 메타데이터(sort)용 정렬 표현. 비검색(QueryDSL) 경로와 검색 경로의 명시 정렬이 같은 표현을 쓴다.
 * 최신순(기본), 가격순(최고가 desc, null 마지막 → 시작가 desc), 좋아요순. 알 수 없는 값은 최신순.
 */
public final class MusicListSort {

    private MusicListSort() {
    }

    public static Sort toSort(String sortBy) {
        if ("price".equals(sortBy)) {
            return Sort.by(Sort.Order.desc("currentHighestBid").nullsLast())
                    .and(Sort.by(Sort.Order.desc("startingPrice")));
        }
        if ("like".equals(sortBy)) {
            return Sort.by(Sort.Direction.DESC, "likeCount");
        }
        return Sort.by(Sort.Direction.DESC, "createdAt");
    }
}
