package com.notenest.domain;

/**
 * 곡 속성(BPM·키) 또는 그 필터 값이 계약을 어겼을 때. 클라이언트가 고칠 수 있는 입력 오류(400)다.
 */
public class InvalidMusicAttributeException extends IllegalArgumentException {

    public InvalidMusicAttributeException(String message) {
        super(message);
    }
}
