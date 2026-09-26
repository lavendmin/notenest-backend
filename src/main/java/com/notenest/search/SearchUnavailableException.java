package com.notenest.search;

/**
 * [NB5] 검색 엔진 장애·색인 부재. 검색어 경로는 503 으로 응답하고, 검색어 없는 목록은 영향받지 않는다.
 * P0 에서는 LIKE 폴백을 하지 않는다(P1 후보, ADR-001).
 */
public class SearchUnavailableException extends RuntimeException {

    public SearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
