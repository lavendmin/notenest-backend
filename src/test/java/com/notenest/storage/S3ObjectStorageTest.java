package com.notenest.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S3 어댑터의 요청 구성과 오류 변환을 AWS 계정 없이 검증한다.
 *  - put·head·delete: S3Client 목으로 요청 필드와 예외 매핑을 확인한다.
 *  - presigned URL: 서명은 로컬 계산이라 가짜 자격증명으로 실제 URL 을 만들어 본다(네트워크 호출 없음).
 * 실제 업로드·private 접근 거부·URL 만료·IAM 권한은 실제 S3 E2E 에서 따로 검증한다.
 */
class S3ObjectStorageTest {

    private static final String BUCKET = "notenest-media-test";

    private final S3Client s3 = mock(S3Client.class);

    @Test
    @DisplayName("put 은 버킷·키·Content-Type·크기와 SHA-256 체크섬 요청을 담아 올리고, 응답 체크섬을 돌려준다")
    void putSendsChecksummedRequest() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().checksumSHA256("c2hhMjU2").build());
        S3ObjectStorage storage = new S3ObjectStorage(s3, null, BUCKET);

        StoredObject stored = storage.put("music/m/full-demo/a", new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, "audio/wav");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));
        PutObjectRequest request = captor.getValue();
        assertThat(request.bucket()).isEqualTo(BUCKET);
        assertThat(request.key()).isEqualTo("music/m/full-demo/a");
        assertThat(request.contentType()).isEqualTo("audio/wav");
        assertThat(request.contentLength()).isEqualTo(3L);
        assertThat(request.checksumAlgorithm()).isEqualTo(ChecksumAlgorithm.SHA256);
        assertThat(stored).isEqualTo(new StoredObject("music/m/full-demo/a", 3, "audio/wav", "c2hhMjU2"));
    }

    @Test
    @DisplayName("head 는 체크섬 모드로 조회해 크기·Content-Type·체크섬을 돌려준다")
    void headReturnsMetadataWithChecksum() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(3L).contentType("audio/wav").checksumSHA256("c2hhMjU2").build());
        S3ObjectStorage storage = new S3ObjectStorage(s3, null, BUCKET);

        assertThat(storage.head("k")).contains(new StoredObject("k", 3, "audio/wav", "c2hhMjU2"));

        ArgumentCaptor<HeadObjectRequest> captor = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3).headObject(captor.capture());
        assertThat(captor.getValue().checksumMode()).isEqualTo(ChecksumMode.ENABLED);
    }

    @Test
    @DisplayName("head 404 는 '없음'(empty), 403 은 권한 오류로 예외 — 없음으로 삼키지 않는다")
    void headDistinguishesNotFoundFromForbidden() {
        S3ObjectStorage storage = new S3ObjectStorage(s3, null, BUCKET);

        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(s3Error(404));
        assertThat(storage.head("missing")).isEmpty();

        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(s3Error(403));
        assertThatThrownBy(() -> storage.head("forbidden"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("403");
    }

    @Test
    @DisplayName("SDK 예외(네트워크 등)는 ObjectStorageException 으로 감싼다 — put·delete")
    void wrapsSdkExceptions() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("connection reset"));
        when(s3.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(SdkClientException.create("connection reset"));
        S3ObjectStorage storage = new S3ObjectStorage(s3, null, BUCKET);

        assertThatThrownBy(() -> storage.put("k", new ByteArrayInputStream(new byte[]{1}), 1, "audio/mpeg"))
                .isInstanceOf(ObjectStorageException.class)
                .hasCauseInstanceOf(SdkClientException.class);
        assertThatThrownBy(() -> storage.delete("k"))
                .isInstanceOf(ObjectStorageException.class);
    }

    @Test
    @DisplayName("버킷이 설정되지 않았으면 S3 를 호출하지 않고 설정 오류로 실패한다")
    void failsFastWithoutBucket() {
        S3ObjectStorage storage = new S3ObjectStorage(s3, null, "");

        assertThatThrownBy(() -> storage.head("k"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("NOTENEST_S3_BUCKET");
        verifyNoInteractions(s3);
    }

    @Test
    @DisplayName("presigned URL 은 private 버킷 객체에 만료 시간과 다운로드 파일명(한글 포함)을 담아 서명된다")
    void presignedUrlCarriesExpiryAndDownloadName() {
        try (S3Presigner presigner = S3Presigner.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secret")))
                .build()) {
            S3ObjectStorage storage = new S3ObjectStorage(s3, presigner, BUCKET);

            URI url = storage.presignedGetUrl("music/m/full-demo/a", Duration.ofMinutes(5), "봄밤 데모.wav");

            String query = URLDecoder.decode(url.getRawQuery(), StandardCharsets.UTF_8);
            assertThat(url.getHost()).startsWith(BUCKET);
            assertThat(url.getPath()).isEqualTo("/music/m/full-demo/a");
            assertThat(query).contains("X-Amz-Expires=300", "X-Amz-Signature=");
            assertThat(query).contains("response-content-disposition=attachment");
            assertThat(query).contains("filename*=UTF-8''%EB%B4%84%EB%B0%A4%20%EB%8D%B0%EB%AA%A8.wav");
        }
    }

    @Test
    @DisplayName("다운로드 헤더는 ASCII 대체 이름과 UTF-8 filename* 를 함께 준다")
    void attachmentHeaderHasAsciiFallbackAndUtf8Name() {
        assertThat(S3ObjectStorage.attachment("봄밤 demo.mp3"))
                .isEqualTo("attachment; filename=\"___demo.mp3\"; filename*=UTF-8''%EB%B4%84%EB%B0%A4%20demo.mp3");
    }

    private static S3Exception s3Error(int status) {
        return (S3Exception) S3Exception.builder().statusCode(status).message("status " + status).build();
    }
}
