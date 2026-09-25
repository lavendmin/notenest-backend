package com.notenest.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 보상 로직 테스트가 기대는 fake 의 실패 주입 의미를 고정한다. */
class FakeObjectStorageTest {

    @Test
    @DisplayName("저장한 객체를 head 로 크기·Content-Type·SHA-256 과 함께 조회하고, 삭제하면 사라진다")
    void putHeadDelete() {
        FakeObjectStorage storage = new FakeObjectStorage();
        byte[] data = "demo".getBytes(StandardCharsets.UTF_8);

        StoredObject stored = put(storage, "music/1/full-demo/a", data);

        assertThat(storage.head("music/1/full-demo/a")).contains(stored);
        assertThat(stored.size()).isEqualTo(4);
        assertThat(stored.sha256Base64()).isEqualTo(FakeObjectStorage.sha256Base64(data));

        storage.delete("music/1/full-demo/a");
        storage.delete("music/1/full-demo/a"); // 없는 키 삭제는 실패하지 않는다

        assertThat(storage.head("music/1/full-demo/a")).isEmpty();
    }

    @Test
    @DisplayName("failPutAt(2): 두 번째 put 만 실패하고 첫 번째 객체는 남는다")
    void failPutAtSecondCall() {
        FakeObjectStorage storage = new FakeObjectStorage().failPutAt(2);

        put(storage, "cover", new byte[]{1});
        assertThatThrownBy(() -> put(storage, "full-demo", new byte[]{2}))
                .isInstanceOf(ObjectStorageException.class);
        put(storage, "preview", new byte[]{3});

        assertThat(storage.keys()).containsExactlyInAnyOrder("cover", "preview");
    }

    @Test
    @DisplayName("failDeleteOf(keyPart): 키에 그 문자열이 들어간 객체의 삭제만 실패하고 객체는 남는다")
    void failDeleteOfKey() {
        FakeObjectStorage storage = new FakeObjectStorage().failDeleteOf("/cover/");
        put(storage, "music/1/cover/a", new byte[]{1});
        put(storage, "music/1/preview/b", new byte[]{2});

        assertThatThrownBy(() -> storage.delete("music/1/cover/a")).isInstanceOf(ObjectStorageException.class);
        storage.delete("music/1/preview/b");

        assertThat(storage.keys()).containsExactly("music/1/cover/a");
    }

    @Test
    @DisplayName("presigned URL 에 만료 시간과 다운로드 파일명이 담긴다")
    void presignedUrlCarriesTtlAndFileName() {
        FakeObjectStorage storage = new FakeObjectStorage();

        String url = storage.presignedGetUrl("music/1/full-demo/a", Duration.ofMinutes(5), "제목.mp3").toString();

        assertThat(url).contains("music/1/full-demo/a", "ttl=300", "filename=");
    }

    private static StoredObject put(FakeObjectStorage storage, String key, byte[] data) {
        return storage.put(key, new ByteArrayInputStream(data), data.length, "application/octet-stream");
    }
}
