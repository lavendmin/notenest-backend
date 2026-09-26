package com.notenest.search;

import java.util.UUID;

/**
 * [NB5] "이 곡의 검색 문서를 원본(MariaDB)에 맞춰 다시 동기화하라". 바뀐 값을 싣지 않는다 — 동기화가 실행 시점의 커밋된 행을
 * 다시 읽으므로 이벤트 순서가 바뀌거나 중복돼도 최종 상태가 원본과 같아진다. 행이 없으면(삭제) 문서를 지운다.
 *
 * @param reason 로그·실패 기록용 원인(create, update, delete, bid, like, auction-end)
 */
public record MusicSearchChangedEvent(UUID musicUuid, String reason) {
}
