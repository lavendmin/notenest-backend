package com.notenest.search;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * [NB5] 곡 저장이 끝난 뒤 호출해 검색 문서 동기화를 요청한다. 실행 시점은 {@link MusicSearchSynchronizer} 가 정한다
 * (활성 트랜잭션이면 커밋 후, 없으면 즉시). 호출 쪽 트랜잭션 경계가 제각각이어도 같은 방식으로 부른다.
 */
@Component
public class MusicSearchEvents {

    private final ApplicationEventPublisher publisher;

    public MusicSearchEvents(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void changed(UUID musicUuid, String reason) {
        publisher.publishEvent(new MusicSearchChangedEvent(musicUuid, reason));
    }
}
