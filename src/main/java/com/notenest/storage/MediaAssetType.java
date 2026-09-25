package com.notenest.storage;

import java.util.Set;

/**
 * 곡 미디어 자산 종류와 업로드 허용 규칙(NB1 확정).
 *
 * 크기 상한은 곡 길이 기준으로 역산했다 — 전체 데모 5~6분, 미리듣기 약 30초.
 *  - 전체 데모: 6분 WAV 24bit/48kHz ≈ 99MiB 까지 수용
 *  - 미리듣기: 30초 MP3 320kbps ≈ 1.2MB 에 여유
 *  - 커버: 3000×3000 JPG 2~5MB, PNG 는 더 클 수 있음
 * 미리듣기 길이는 검사하지 않는다(서버가 음원을 해석하지 않음) — "30초 미리듣기"가 아니라 "미리듣기 음원"이다.
 */
public enum MediaAssetType {

    COVER("커버 이미지", "cover", 10L * 1024 * 1024, Set.of(DetectedMedia.JPEG, DetectedMedia.PNG)),
    PREVIEW("미리듣기", "preview", 5L * 1024 * 1024, Set.of(DetectedMedia.MP3)),
    FULL_DEMO("전체 데모", "full-demo", 100L * 1024 * 1024, Set.of(DetectedMedia.MP3, DetectedMedia.WAV));

    private final String label;
    private final String keySegment;
    private final long maxBytes;
    private final Set<DetectedMedia> allowed;

    MediaAssetType(String label, String keySegment, long maxBytes, Set<DetectedMedia> allowed) {
        this.label = label;
        this.keySegment = keySegment;
        this.maxBytes = maxBytes;
        this.allowed = allowed;
    }

    /** 사용자 메시지용 이름. */
    public String label() {
        return label;
    }

    /** 객체 키 경로 조각 — music/{musicUuid}/{keySegment}/{assetUuid}. */
    public String keySegment() {
        return keySegment;
    }

    public long maxBytes() {
        return maxBytes;
    }

    public boolean allows(DetectedMedia media) {
        return allowed.contains(media);
    }

    /** 파일 앞부분 시그니처로 판별한 실제 형식. 저장 시 Content-Type 은 클라이언트 선언값이 아니라 이 값을 쓴다. */
    public enum DetectedMedia {
        JPEG("image/jpeg"),
        PNG("image/png"),
        MP3("audio/mpeg"),
        WAV("audio/wav");

        private final String contentType;

        DetectedMedia(String contentType) {
            this.contentType = contentType;
        }

        public String contentType() {
            return contentType;
        }
    }
}
