package com.notenest.storage;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * private S3 버킷 기반 {@link ObjectStorage}.
 *
 * - 자격증명은 코드·설정 파일에 두지 않고 SDK 기본 자격증명 체인(환경변수 등)으로 읽는다.
 * - put 은 SHA-256 체크섬을 함께 기록하고, head 는 그 체크섬을 돌려준다(백필 대조용).
 * - SDK 예외(권한·네트워크·서비스 오류)는 {@link ObjectStorageException} 으로 감싸 서비스의 보상 로직이 한 타입으로 분기하게 한다.
 * - presigned URL 에는 서명이 담겨 있으므로 로그·예외 메시지에 남기지 않는다.
 */
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3ObjectStorage(S3Client s3, S3Presigner presigner, String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public StoredObject put(String key, InputStream data, long size, String contentType) {
        requireBucket();
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(size)
                .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                .build();
        try {
            PutObjectResponse response = s3.putObject(request, RequestBody.fromInputStream(data, size));
            return new StoredObject(key, size, contentType, response.checksumSHA256());
        } catch (SdkException e) {
            throw new ObjectStorageException("객체 업로드 실패: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        requireBucket();
        try {
            // S3 는 없는 키 삭제도 성공(204)으로 응답한다 — 인터페이스 계약과 같다.
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException e) {
            throw new ObjectStorageException("객체 삭제 실패: " + key, e);
        }
    }

    @Override
    public Optional<StoredObject> head(String key) {
        requireBucket();
        try {
            HeadObjectResponse response = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .checksumMode(ChecksumMode.ENABLED)
                    .build());
            return Optional.of(new StoredObject(key, response.contentLength(), response.contentType(), response.checksumSHA256()));
        } catch (S3Exception e) {
            // 404 만 "없음"이다. 403 은 권한 문제라 없음으로 삼키지 않는다(ListBucket 권한이 없으면 없는 키도 403).
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new ObjectStorageException("객체 조회 실패(HTTP " + e.statusCode() + "): " + key, e);
        } catch (SdkException e) {
            throw new ObjectStorageException("객체 조회 실패: " + key, e);
        }
    }

    @Override
    public URI presignedGetUrl(String key, Duration ttl, String downloadFileName) {
        requireBucket();
        GetObjectRequest.Builder getObject = GetObjectRequest.builder().bucket(bucket).key(key);
        if (downloadFileName != null && !downloadFileName.isBlank()) {
            getObject.responseContentDisposition(attachment(downloadFileName));
        }
        try {
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(ttl)
                            .getObjectRequest(getObject.build())
                            .build())
                    .url().toURI();
        } catch (SdkException | URISyntaxException e) {
            throw new ObjectStorageException("다운로드 URL 발급 실패: " + key, e);
        }
    }

    /**
     * RFC 6266/5987 형식의 다운로드 헤더. 한글 파일명은 filename* 로 UTF-8 인코딩하고,
     * filename* 를 모르는 클라이언트를 위해 ASCII 대체 이름을 함께 준다.
     */
    static String attachment(String fileName) {
        String asciiFallback = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded;
    }

    private void requireBucket() {
        if (bucket == null || bucket.isBlank()) {
            throw new ObjectStorageException("S3 버킷이 설정되지 않았습니다(NOTENEST_S3_BUCKET).");
        }
    }
}
