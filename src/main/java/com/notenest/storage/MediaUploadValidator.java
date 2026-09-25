package com.notenest.storage;

import com.notenest.storage.MediaAssetType.DetectedMedia;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * 업로드 파일의 크기와 실제 형식을 자산 종류 규칙({@link MediaAssetType})에 맞춰 검사한다.
 *
 * 형식은 클라이언트가 보낸 Content-Type·확장자가 아니라 파일 앞부분 시그니처(매직 넘버)로 판별한다.
 * 시그니처 검사는 "선언과 실제가 다른 파일"을 거르는 것이지 음원 전체의 유효성(디코딩 가능 여부)을 보장하지 않는다.
 */
@Component
public class MediaUploadValidator {

    private static final int HEADER_BYTES = 12;

    /** 검사를 통과하면 저장에 쓸 실제 형식을 돌려준다. */
    public DetectedMedia validate(MediaAssetType type, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidMediaException(type + " 파일이 비어 있습니다.");
        }
        if (file.getSize() > type.maxBytes()) {
            throw new InvalidMediaException(type + " 파일은 " + (type.maxBytes() / (1024 * 1024)) + "MB 이하여야 합니다.");
        }

        DetectedMedia detected = detect(readHeader(file))
                .orElseThrow(() -> new InvalidMediaException(type + " 파일 형식을 확인할 수 없습니다."));
        if (!type.allows(detected)) {
            throw new InvalidMediaException(type + " 파일로 " + detected + " 형식은 허용되지 않습니다.");
        }
        return detected;
    }

    private static byte[] readHeader(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(HEADER_BYTES);
        } catch (IOException e) {
            throw new InvalidMediaException("업로드 파일을 읽을 수 없습니다.");
        }
    }

    static Optional<DetectedMedia> detect(byte[] h) {
        if (startsWith(h, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(DetectedMedia.JPEG);
        }
        if (startsWith(h, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(DetectedMedia.PNG);
        }
        // WAV: "RIFF" <size 4바이트> "WAVE"
        if (startsWith(h, 'R', 'I', 'F', 'F') && h.length >= 12
                && h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E') {
            return Optional.of(DetectedMedia.WAV);
        }
        // MP3: ID3v2 태그로 시작하거나, 태그 없이 MPEG 오디오 Layer III 프레임 동기 비트로 시작한다.
        if (startsWith(h, 'I', 'D', '3')) {
            return Optional.of(DetectedMedia.MP3);
        }
        if (h.length >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0 && ((h[1] >> 1) & 0x03) == 0x01) {
            return Optional.of(DetectedMedia.MP3);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] h, int... signature) {
        if (h.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((h[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
