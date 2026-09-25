package com.notenest.storage;

import com.notenest.domain.MediaObject;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 곡 미디어의 만료 시간 있는 URL 을 발급한다. 버킷은 private 이라 모든 전달은 이 URL 로만 한다.
 *
 * 만료 시간은 노출 범위에 맞춰 다르게 둔다.
 *  - 커버(목록·상세, 비로그인 목록 포함): 1시간 — 목록을 오래 띄워 둬도 이미지가 깨지지 않게
 *  - 미리듣기(로그인 상세): 10분
 *  - 전체 데모(판매자·결제 완료 낙찰자, 권한 확인 후): 5분 — 만료 전에는 재사용·공유될 수 있어 1회용이 아니다
 */
@Component
public class MediaUrlIssuer {

    static final Duration COVER_TTL = Duration.ofHours(1);
    static final Duration PREVIEW_TTL = Duration.ofMinutes(10);
    public static final Duration FULL_DEMO_TTL = Duration.ofMinutes(5);

    private final ObjectStorage storage;

    public MediaUrlIssuer(ObjectStorage storage) {
        this.storage = storage;
    }

    /** 커버 URL. 키가 없으면(기존 곡) null. */
    public String coverUrl(String coverObjectKey) {
        return coverObjectKey == null ? null : storage.presignedGetUrl(coverObjectKey, COVER_TTL, null).toString();
    }

    public String coverUrl(MediaObject cover) {
        return coverUrl(keyOf(cover));
    }

    /** 미리듣기 URL. 미리듣기가 없는 곡(기존 곡)은 null — 전체 데모로 대체하지 않는다. */
    public String previewUrl(MediaObject preview) {
        String key = keyOf(preview);
        return key == null ? null : storage.presignedGetUrl(key, PREVIEW_TTL, null).toString();
    }

    /** 전체 데모 다운로드 URL. 권한 판단(FullDemoAccessPolicy)을 통과한 뒤에만 호출한다. */
    public String fullDemoUrl(MediaObject fullDemo, String downloadFileName) {
        return storage.presignedGetUrl(fullDemo.getObjectKey(), FULL_DEMO_TTL, downloadFileName).toString();
    }

    public static boolean hasObjectKey(MediaObject media) {
        return keyOf(media) != null;
    }

    private static String keyOf(MediaObject media) {
        return media == null ? null : media.getObjectKey();
    }
}
