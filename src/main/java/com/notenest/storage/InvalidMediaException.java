package com.notenest.storage;

/** 업로드 파일이 자산 종류의 형식·크기 규칙을 어겼다. 클라이언트 입력 오류(400)로 응답한다. */
public class InvalidMediaException extends RuntimeException {

    public InvalidMediaException(String message) {
        super(message);
    }
}
