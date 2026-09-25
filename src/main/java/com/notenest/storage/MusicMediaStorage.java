package com.notenest.storage;

import com.notenest.domain.MediaObject;
import com.notenest.storage.MediaAssetType.DetectedMedia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 곡 미디어 업로드와 보상 삭제.
 *
 * 객체 저장소와 DB 는 한 트랜잭션으로 묶이지 않는다. 그래서 두 가지 보상을 둔다.
 *  - 여러 파일 중 하나의 업로드가 실패하면, 이미 올린 파일을 지우고 실패를 그대로 던진다({@link #uploadAll}).
 *  - 업로드 뒤 DB 저장이 실패하면, 호출한 서비스가 {@link #deleteQuietly} 로 올린 파일을 지운다.
 * 보상 삭제마저 실패하면 원래 실패를 가리지 않도록 예외를 삼키고, 남은 고아 객체 키를 WARN 로그로 남긴다.
 */
@Component
public class MusicMediaStorage {

    private static final Logger log = LoggerFactory.getLogger(MusicMediaStorage.class);
    private static final int MAX_ORIGINAL_NAME = 255;

    private final ObjectStorage storage;

    public MusicMediaStorage(ObjectStorage storage) {
        this.storage = storage;
    }

    /** 검증을 통과한 업로드 한 건 — 저장 Content-Type 은 선언값이 아니라 판별한 실제 형식을 쓴다. */
    public record Upload(MediaAssetType type, MultipartFile file, DetectedMedia media) {
    }

    /**
     * 주어진 순서대로 올린다. 하나라도 실패하면 이미 올린 객체를 지우고 {@link ObjectStorageException} 을 던진다.
     * 성공하면 자산별 객체 참조를 돌려준다.
     */
    public Map<MediaAssetType, MediaObject> uploadAll(UUID musicUuid, List<Upload> uploads) {
        Map<MediaAssetType, MediaObject> stored = new EnumMap<>(MediaAssetType.class);
        for (Upload upload : uploads) {
            try {
                stored.put(upload.type(), upload(musicUuid, upload));
            } catch (RuntimeException e) {
                deleteQuietly(keysOf(stored.values()));
                throw e instanceof ObjectStorageException ? e
                        : new ObjectStorageException(upload.type().label() + " 업로드 실패", e);
            }
        }
        return stored;
    }

    /** 보상 삭제. 실패해도 예외를 던지지 않고 고아 객체로 기록한다. */
    public void deleteQuietly(Collection<String> keys) {
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (RuntimeException e) {
                log.warn("[MEDIA] 보상 삭제 실패 — 고아 객체 남음 key={}", key, e);
            }
        }
    }

    public static List<String> keysOf(Collection<MediaObject> objects) {
        List<String> keys = new ArrayList<>();
        for (MediaObject object : objects) {
            if (object != null && object.getObjectKey() != null) {
                keys.add(object.getObjectKey());
            }
        }
        return keys;
    }

    /** music/{musicUuid}/{cover|preview|full-demo}/{assetUuid} — 교체 시 새 키로 올려 옛 객체를 덮어쓰지 않는다. */
    static String newKey(UUID musicUuid, MediaAssetType type) {
        return "music/" + musicUuid + "/" + type.keySegment() + "/" + UUID.randomUUID();
    }

    private MediaObject upload(UUID musicUuid, Upload upload) {
        String key = newKey(musicUuid, upload.type());
        MultipartFile file = upload.file();
        String contentType = upload.media().contentType();
        try (InputStream in = file.getInputStream()) {
            StoredObject stored = storage.put(key, in, file.getSize(), contentType);
            return new MediaObject(stored.key(), contentType, stored.size(), originalName(file, upload.type()));
        } catch (IOException e) {
            throw new ObjectStorageException("업로드 파일을 읽을 수 없습니다: " + key, e);
        }
    }

    // 다운로드 파일명·확장자 결정용. 경로 조각을 떼고 컬럼 길이에 맞춘다.
    private static String originalName(MultipartFile file, MediaAssetType type) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return type.keySegment();
        }
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        return name.length() > MAX_ORIGINAL_NAME ? name.substring(name.length() - MAX_ORIGINAL_NAME) : name;
    }
}
