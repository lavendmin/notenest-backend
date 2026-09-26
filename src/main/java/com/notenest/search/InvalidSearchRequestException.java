package com.notenest.search;

/**
 * [NB5] 검색 경로가 지원하지 않는 요청(400). 예: 검색 결과의 10,000번째 이후 페이지(Elasticsearch max_result_window).
 */
public class InvalidSearchRequestException extends IllegalArgumentException {

    public InvalidSearchRequestException(String message) {
        super(message);
    }
}
