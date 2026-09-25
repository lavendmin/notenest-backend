package com.notenest.storage;

/**
 * 저장된 객체의 메타데이터.
 *
 * @param sha256Base64 저장 시 기록한 SHA-256 체크섬(Base64). 백필 검증에서 원본 LOB 해시와 대조한다.
 */
public record StoredObject(String key, long size, String contentType, String sha256Base64) {
}
