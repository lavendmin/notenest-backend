package com.notenest.storage;

import com.notenest.storage.MediaAssetType.DetectedMedia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaUploadValidatorTest {

    private static final byte[] JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1);
    private static final byte[] PNG = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D);
    private static final byte[] WAV = bytes('R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'A', 'V', 'E', 'f', 'm', 't', ' ');
    private static final byte[] MP3_ID3 = bytes('I', 'D', '3', 4, 0, 0, 0, 0, 0, 0);
    private static final byte[] MP3_FRAME = bytes(0xFF, 0xFB, 0x90, 0x64, 0, 0); // MPEG-1 Layer III, 태그 없음
    private static final byte[] MP2_FRAME = bytes(0xFF, 0xFD, 0x90, 0x64, 0, 0); // MPEG-1 Layer II — MP3 아님

    private final MediaUploadValidator validator = new MediaUploadValidator();

    @Test
    @DisplayName("자산 종류별 허용 형식을 시그니처로 판별해 실제 Content-Type 을 돌려준다")
    void acceptsAllowedFormats() {
        assertThat(validator.validate(MediaAssetType.COVER, file(JPEG, "image/jpeg"))).isEqualTo(DetectedMedia.JPEG);
        assertThat(validator.validate(MediaAssetType.COVER, file(PNG, "image/png"))).isEqualTo(DetectedMedia.PNG);
        assertThat(validator.validate(MediaAssetType.PREVIEW, file(MP3_ID3, "audio/mpeg"))).isEqualTo(DetectedMedia.MP3);
        assertThat(validator.validate(MediaAssetType.PREVIEW, file(MP3_FRAME, "audio/mpeg"))).isEqualTo(DetectedMedia.MP3);
        assertThat(validator.validate(MediaAssetType.FULL_DEMO, file(MP3_ID3, "audio/mpeg"))).isEqualTo(DetectedMedia.MP3);
        assertThat(validator.validate(MediaAssetType.FULL_DEMO, file(WAV, "audio/wav"))).isEqualTo(DetectedMedia.WAV);
        assertThat(DetectedMedia.WAV.contentType()).isEqualTo("audio/wav");
    }

    @Test
    @DisplayName("자산 종류에 허용되지 않은 형식은 거부한다 — 미리듣기 WAV, 커버 MP3, 전체 데모 PNG")
    void rejectsDisallowedFormats() {
        assertRejected(MediaAssetType.PREVIEW, file(WAV, "audio/wav"), "허용되지 않습니다");
        assertRejected(MediaAssetType.COVER, file(MP3_ID3, "image/jpeg"), "허용되지 않습니다");
        assertRejected(MediaAssetType.FULL_DEMO, file(PNG, "audio/mpeg"), "허용되지 않습니다");
    }

    @Test
    @DisplayName("클라이언트 선언 Content-Type 이 아니라 실제 바이트로 판별한다 — 선언이 맞아도 내용이 다르면 거부")
    void ignoresDeclaredContentType() {
        byte[] text = "not really audio".getBytes();
        assertRejected(MediaAssetType.FULL_DEMO, file(text, "audio/mpeg"), "형식을 확인할 수 없습니다");
        assertRejected(MediaAssetType.PREVIEW, file(MP2_FRAME, "audio/mpeg"), "형식을 확인할 수 없습니다");
    }

    @Test
    @DisplayName("빈 파일과 누락된 파일은 거부한다")
    void rejectsEmptyOrMissingFile() {
        assertRejected(MediaAssetType.COVER, file(new byte[0], "image/png"), "비어 있습니다");
        assertRejected(MediaAssetType.COVER, null, "비어 있습니다");
    }

    @Test
    @DisplayName("자산 종류별 크기 상한을 넘으면 거부하고, 상한과 같은 크기는 허용한다")
    void enforcesSizeLimitPerType() {
        assertRejected(MediaAssetType.PREVIEW, sized(MP3_ID3, 5L * 1024 * 1024 + 1), "5MB 이하");
        assertRejected(MediaAssetType.COVER, sized(PNG, 10L * 1024 * 1024 + 1), "10MB 이하");
        assertRejected(MediaAssetType.FULL_DEMO, sized(WAV, 100L * 1024 * 1024 + 1), "100MB 이하");

        assertThat(validator.validate(MediaAssetType.FULL_DEMO, sized(WAV, 100L * 1024 * 1024))).isEqualTo(DetectedMedia.WAV);
    }

    private void assertRejected(MediaAssetType type, MultipartFile file, String messagePart) {
        assertThatThrownBy(() -> validator.validate(type, file))
                .isInstanceOf(InvalidMediaException.class)
                .hasMessageContaining(messagePart);
    }

    private static MultipartFile file(byte[] content, String declaredContentType) {
        return new MockMultipartFile("file", "upload", declaredContentType, content);
    }

    // 크기 상한 검사용 — 실제 100MB 를 할당하지 않고 크기만 크게 보고하는 파일.
    private static MultipartFile sized(byte[] header, long size) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(size);
        try {
            when(file.getInputStream()).thenAnswer(inv -> new ByteArrayInputStream(Arrays.copyOf(header, header.length)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return file;
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
