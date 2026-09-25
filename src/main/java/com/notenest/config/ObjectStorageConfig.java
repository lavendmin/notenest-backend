package com.notenest.config;

import com.notenest.storage.ObjectStorage;
import com.notenest.storage.S3ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 클라이언트·presigner·{@link ObjectStorage} 빈.
 *
 * 자격증명은 SDK 기본 체인(AWS_ACCESS_KEY_ID·AWS_SECRET_ACCESS_KEY 환경변수 등)이 실제 요청 시점에 찾는다.
 * 그래서 자격증명·버킷이 없는 로컬·테스트 환경에서도 애플리케이션은 기동되고, S3 를 실제로 호출할 때만 실패한다.
 */
@Configuration
public class ObjectStorageConfig {

    @Bean(destroyMethod = "close")
    public S3Client s3Client(@Value("${notenest.storage.s3.region}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner(@Value("${notenest.storage.s3.region}") String region) {
        return S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build();
    }

    @Bean
    public ObjectStorage objectStorage(S3Client s3Client, S3Presigner s3Presigner,
                                       @Value("${notenest.storage.s3.bucket}") String bucket) {
        return new S3ObjectStorage(s3Client, s3Presigner, bucket);
    }
}
