package com.notenest.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 테스트 전용 {@link ObjectStorage} — 메모리에 저장하고 실패를 주입할 수 있다.
 *
 * 목적은 S3 동작 흉내가 아니라 서비스의 부분 실패 보상 검증이다:
 *  - {@link #failPutAt(int)}: N번째 put 을 실패시킨다 (예: 커버는 올라가고 전체 데모 업로드에서 실패)
 *  - {@link #failDeleteOf(String)}: 키에 주어진 문자열이 들어간 객체의 삭제를 실패시킨다 (예: "/cover/" — 보상 삭제 실패)
 * AWS 연동(IAM·private 접근·URL 만료)은 여기서 검증하지 않는다.
 */
public class FakeObjectStorage implements ObjectStorage {

    private final Map<String, byte[]> objects = new LinkedHashMap<>();
    private final Map<String, String> contentTypes = new LinkedHashMap<>();
    private final Set<String> failingDeletes = new HashSet<>();
    private int putCalls;
    private int failingPutCall = -1;

    /** n번째(1부터) put 호출을 실패시킨다. 그 전후 호출은 정상 저장된다. */
    public FakeObjectStorage failPutAt(int n) {
        this.failingPutCall = n;
        return this;
    }

    /** 키에 keyPart 가 들어간 객체의 delete 를 실패시킨다. 키가 무작위 UUID 를 포함하므로 부분 일치로 지정한다. */
    public FakeObjectStorage failDeleteOf(String keyPart) {
        failingDeletes.add(keyPart);
        return this;
    }

    /** 저장 내용과 주입한 실패를 모두 지운다 — 스프링 컨텍스트에서 테스트 간 공유될 때 사용. */
    public void reset() {
        objects.clear();
        contentTypes.clear();
        failingDeletes.clear();
        putCalls = 0;
        failingPutCall = -1;
    }

    public Set<String> keys() {
        return Set.copyOf(objects.keySet());
    }

    public byte[] bytes(String key) {
        return objects.get(key);
    }

    @Override
    public StoredObject put(String key, InputStream data, long size, String contentType) {
        putCalls++;
        if (putCalls == failingPutCall) {
            throw new ObjectStorageException("주입된 put 실패: " + key);
        }
        byte[] bytes;
        try {
            bytes = data.readAllBytes();
        } catch (IOException e) {
            throw new ObjectStorageException("업로드 스트림을 읽을 수 없습니다: " + key, e);
        }
        if (bytes.length != size) {
            throw new ObjectStorageException("선언 크기(" + size + ")와 실제 크기(" + bytes.length + ")가 다릅니다: " + key);
        }
        objects.put(key, bytes);
        contentTypes.put(key, contentType);
        return new StoredObject(key, bytes.length, contentType, sha256Base64(bytes));
    }

    @Override
    public void delete(String key) {
        if (failingDeletes.stream().anyMatch(key::contains)) {
            throw new ObjectStorageException("주입된 delete 실패: " + key);
        }
        objects.remove(key);
        contentTypes.remove(key);
    }

    @Override
    public Optional<StoredObject> head(String key) {
        byte[] bytes = objects.get(key);
        if (bytes == null) {
            return Optional.empty();
        }
        return Optional.of(new StoredObject(key, bytes.length, contentTypes.get(key), sha256Base64(bytes)));
    }

    @Override
    public URI presignedGetUrl(String key, Duration ttl, String downloadFileName) {
        String query = "ttl=" + ttl.toSeconds()
                + (downloadFileName == null ? "" : "&filename=" + URLEncoder.encode(downloadFileName, StandardCharsets.UTF_8));
        return URI.create("https://fake-storage.local/" + key + "?" + query);
    }

    static String sha256Base64(byte[] bytes) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
