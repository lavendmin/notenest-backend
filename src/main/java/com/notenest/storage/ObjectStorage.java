package com.notenest.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * 곡 미디어(커버·미리듣기·전체 데모) 객체 저장소.
 *
 * 분리한 이유는 공급자 교체가 아니라 두 가지다.
 *  1) 객체 저장소와 DB 는 하나의 트랜잭션으로 묶이지 않는다. 업로드 후 DB 저장 실패, 파일 교체 중 실패 같은
 *     부분 실패를 테스트에서 재현하고 보상 로직(고아 객체 삭제, 기존 파일 보존)을 검증하려면 저장소 경계가 필요하다.
 *  2) AWS SDK 의존을 한 곳(S3 구현)에 가둔다.
 *
 * 구현은 실제 S3 구현과 테스트 전용 fake(실패 주입용) 둘뿐이다. fake 테스트는 서비스의 보상 로직을 검증할 뿐이고,
 * IAM 권한·private 접근 거부·presigned URL 만료 같은 AWS 연동은 실제 S3 에서 따로 검증한다.
 *
 * 모든 실패는 {@link ObjectStorageException} 으로 던진다.
 */
public interface ObjectStorage {

    /**
     * 객체를 저장한다. 같은 키가 있으면 덮어쓴다(교체는 새 키로 올리고 DB 전환 후 옛 키를 지우는 방식으로 한다).
     * 저장 시 SHA-256 체크섬을 함께 기록해 {@link #head} 로 내용 대조(백필 검증)를 할 수 있게 한다.
     */
    StoredObject put(String key, InputStream data, long size, String contentType);

    /** 객체를 삭제한다. 없는 키를 지워도 실패하지 않는다. */
    void delete(String key);

    /** 객체의 존재·크기·Content-Type·체크섬을 조회한다. 없으면 empty. */
    Optional<StoredObject> head(String key);

    /**
     * 만료 시간이 있는 다운로드 URL 을 발급한다. 권한 판단은 호출하는 쪽(FullDemoAccessPolicy)의 책임이다.
     * 발급된 URL 은 만료 전까지 재사용·공유될 수 있으므로 1회용 다운로드를 보장하지 않는다.
     *
     * @param downloadFileName 브라우저가 저장할 파일명(Content-Disposition). null 이면 지정하지 않는다.
     */
    URI presignedGetUrl(String key, Duration ttl, String downloadFileName);
}
