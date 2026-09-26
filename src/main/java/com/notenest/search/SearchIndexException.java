package com.notenest.search;

/**
 * [NB5] 색인 쓰기·조회 실패(연결 실패·타임아웃·서버 오류). 동기화는 이를 제한된 횟수만큼 재시도한 뒤 실패로 기록하고 삼킨다 —
 * DB 저장은 이미 성공했으므로 쓰기 요청을 실패로 돌려주지 않는다. 누락분은 대조 작업이 복구한다.
 */
public class SearchIndexException extends RuntimeException {

    public SearchIndexException(String message, Throwable cause) {
        super(message, cause);
    }
}
