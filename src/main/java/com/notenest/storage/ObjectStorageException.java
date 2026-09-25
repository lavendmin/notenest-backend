package com.notenest.storage;

/** 객체 저장소 작업(put·delete·head·URL 발급) 실패. 보상 로직은 이 예외를 기준으로 분기한다. */
public class ObjectStorageException extends RuntimeException {

    public ObjectStorageException(String message) {
        super(message);
    }

    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
