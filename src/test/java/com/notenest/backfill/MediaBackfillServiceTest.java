package com.notenest.backfill;

import com.notenest.backfill.MediaBackfillService.BackfillReport;
import com.notenest.backfill.MediaBackfillService.VerificationReport;
import com.notenest.config.QueryDslConfig;
import com.notenest.domain.MediaObject;
import com.notenest.domain.Music;
import com.notenest.domain.User;
import com.notenest.repository.MusicRepository;
import com.notenest.repository.UserRepository;
import com.notenest.storage.FakeObjectStorage;
import com.notenest.storage.MediaAssetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기존 곡 LOB → 객체 저장소 백필의 재실행 안전성과 대조를 검증한다(저장 구조 전환 리허설).
 *
 * 곡마다 실제 커밋되는 트랜잭션으로 처리하는지까지 보려고 테스트 메서드는 비트랜잭션(NOT_SUPPORTED)으로 돌리고
 * 매 테스트 전에 직접 비운다. 저장소는 실패 주입이 가능한 {@link FakeObjectStorage} 다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueryDslConfig.class, MediaBackfillService.class, MediaBackfillServiceTest.FakeStorageConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        // user 는 H2 예약어라 @Table(name="user") DDL/쿼리가 깨진다 → NON_KEYWORDS 로 제외.
        "spring.datasource.url=jdbc:h2:mem:media-backfill;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
class MediaBackfillServiceTest {

    // 실제 형식 시그니처를 가진 LOB 와, 형식을 판별할 수 없는 시드 더미(REPEAT('a'/'i') 흉내)
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 1, 2};
    private static final byte[] WAV = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'A', 'V', 'E', 3, 4};
    private static final byte[] DUMMY_IMAGE = "iiiiiiiiiiiiiiii".getBytes();
    private static final byte[] DUMMY_AUDIO = "aaaaaaaaaaaaaaaaaaaaaaaa".getBytes();

    @TestConfiguration
    static class FakeStorageConfig {
        @Bean
        FakeObjectStorage fakeObjectStorage() {
            return new FakeObjectStorage();
        }
    }

    @Autowired private MediaBackfillService backfill;
    @Autowired private FakeObjectStorage storage;
    @Autowired private MusicRepository musicRepository;
    @Autowired private UserRepository userRepository;

    private User owner;

    @BeforeEach
    void reset() {
        storage.reset();
        musicRepository.deleteAll();
        userRepository.deleteAll();
        owner = new User();
        owner.setEmail("owner@test.local");
        owner.setNickname("owner");
        owner.setName("owner");
        owner.setPassword("x");
        owner.setRole("ROLE_USER");
        owner.setEmailVerified(true);
        owner.setAgreement(true);
        owner = userRepository.save(owner);
    }

    @Test
    @DisplayName("LOB 를 결정적 키로 옮기고 형식을 판별해 기록한다 — 미리듣기는 만들지 않고 LOB 는 남긴다, 대조 누락 0")
    void migratesLobsToDeterministicKeys() {
        Music real = saveLegacy(PNG, WAV);
        Music seed = saveLegacy(DUMMY_IMAGE, DUMMY_AUDIO);

        BackfillReport report = backfill.backfill();

        assertThat(report.candidates()).isEqualTo(2);
        assertThat(report.migratedSongs()).isEqualTo(2);
        assertThat(report.uploaded()).isEqualTo(4);
        assertThat(report.failedSongs()).isZero();

        Music realAfter = reload(real);
        assertThat(realAfter.getCover().getObjectKey()).isEqualTo("music/" + real.getMusicUuid() + "/cover/legacy");
        assertThat(realAfter.getCover().getContentType()).isEqualTo("image/png");
        assertThat(realAfter.getFullDemo().getObjectKey()).isEqualTo("music/" + real.getMusicUuid() + "/full-demo/legacy");
        assertThat(realAfter.getFullDemo().getContentType()).isEqualTo("audio/wav");
        assertThat(realAfter.getFullDemo().getSize()).isEqualTo(WAV.length);
        assertThat(realAfter.getPreview()).as("기존 곡은 미리듣기 없음").isNull();
        assertThat(realAfter.getAudio()).as("LOB 는 대조·삭제 마이그레이션 전까지 남긴다").isEqualTo(WAV);
        assertThat(storage.bytes(realAfter.getFullDemo().getObjectKey())).isEqualTo(WAV);

        assertThat(reload(seed).getFullDemo().getContentType()).as("시드 더미는 형식 판별 불가").isEqualTo("application/octet-stream");

        VerificationReport verification = backfill.verify();
        assertThat(verification.clean()).isTrue();
        assertThat(verification.checkedObjects()).isEqualTo(4);
    }

    @Test
    @DisplayName("재실행은 이전된 곡을 건너뛴다 — 대상 0, 업로드 0, 객체 수 그대로")
    void rerunSkipsMigratedSongs() {
        saveLegacy(PNG, WAV);
        backfill.backfill();
        int putsAfterFirstRun = storage.putCalls();
        var keysAfterFirstRun = storage.keys();

        BackfillReport rerun = backfill.backfill();

        assertThat(rerun.candidates()).isZero();
        assertThat(storage.putCalls()).isEqualTo(putsAfterFirstRun);
        assertThat(storage.keys()).isEqualTo(keysAfterFirstRun);
    }

    @Test
    @DisplayName("일부만 이전된 곡은 남은 자산만 옮긴다 — 이미 있는 커버 키는 건드리지 않는다")
    void migratesOnlyMissingAssets() {
        Music music = saveLegacy(PNG, WAV);
        MediaObject existingCover = new MediaObject("music/" + music.getMusicUuid() + "/cover/new-upload", "image/png", 1L, "c.png");
        music.setCover(existingCover);
        musicRepository.save(music);

        BackfillReport report = backfill.backfill();

        assertThat(report.uploaded()).isEqualTo(1);
        Music after = reload(music);
        assertThat(after.getCover().getObjectKey()).isEqualTo(existingCover.getObjectKey());
        assertThat(after.getFullDemo().getObjectKey()).endsWith("/full-demo/legacy");
    }

    @Test
    @DisplayName("업로드 후 DB 갱신 전에 멈췄던 곡: 재실행 시 같은 키·크기·해시의 객체를 재사용하고 다시 올리지 않는다")
    void reusesObjectUploadedBeforeInterruption() {
        Music music = saveLegacy(PNG, WAV);
        String coverKey = MediaBackfillService.legacyKey(music.getMusicUuid(), MediaAssetType.COVER);
        storage.put(coverKey, new ByteArrayInputStream(PNG), PNG.length, "image/png"); // 이전 실행이 여기까지 하고 멈춤

        BackfillReport report = backfill.backfill();

        assertThat(report.reused()).isEqualTo(1);
        assertThat(report.uploaded()).isEqualTo(1); // 전체 데모만 새로 올림
        assertThat(reload(music).getCover().getObjectKey()).isEqualTo(coverKey);
        assertThat(backfill.verify().clean()).isTrue();
    }

    @Test
    @DisplayName("같은 키에 내용이 다른 객체가 있으면 재사용하지 않고 LOB 로 다시 올린다")
    void overwritesStaleObjectAtDeterministicKey() {
        Music music = saveLegacy(PNG, WAV);
        String coverKey = MediaBackfillService.legacyKey(music.getMusicUuid(), MediaAssetType.COVER);
        byte[] stale = PNG.clone();
        stale[stale.length - 1] = 99; // 크기는 같고 내용만 다름
        storage.put(coverKey, new ByteArrayInputStream(stale), stale.length, "image/png");

        BackfillReport report = backfill.backfill();

        assertThat(report.reused()).isZero();
        assertThat(storage.bytes(coverKey)).isEqualTo(PNG);
        assertThat(backfill.verify().clean()).isTrue();
    }

    @Test
    @DisplayName("중간 실패: 실패한 곡만 남고 다음 곡은 계속 처리된다 — 장애 해소 후 재실행하면 누락 0")
    void failedSongIsRetriedOnRerun() {
        saveLegacy(PNG, WAV);
        saveLegacy(PNG, WAV);
        storage.failPutAt(2); // 첫 곡의 전체 데모 업로드 실패(커버는 이미 올라감)

        BackfillReport first = backfill.backfill();

        assertThat(first.failedSongs()).isEqualTo(1);
        assertThat(first.migratedSongs()).isEqualTo(1);
        assertThat(backfill.verify().missingKeys()).as("실패한 곡의 두 자산이 누락으로 잡힌다").isEqualTo(2);

        storage.clearFailures();
        BackfillReport rerun = backfill.backfill();

        assertThat(rerun.candidates()).isEqualTo(1);
        assertThat(rerun.reused()).as("실패 전 올라간 커버는 재사용").isEqualTo(1);
        assertThat(rerun.failedSongs()).isZero();
        VerificationReport verification = backfill.verify();
        assertThat(verification.clean()).isTrue();
        assertThat(verification.checkedObjects()).isEqualTo(4);
    }

    @Test
    @DisplayName("대조는 객체 누락·해시 불일치를 실제로 잡아낸다")
    void verificationDetectsMissingAndCorruptedObjects() {
        Music a = saveLegacy(PNG, WAV);
        Music b = saveLegacy(PNG, WAV);
        backfill.backfill();

        storage.delete(reload(a).getFullDemo().getObjectKey());                   // 객체 누락
        String bCover = reload(b).getCover().getObjectKey();
        byte[] corrupted = PNG.clone();
        corrupted[corrupted.length - 1] = 42;
        storage.put(bCover, new ByteArrayInputStream(corrupted), corrupted.length, "image/png"); // 같은 크기, 다른 내용

        VerificationReport verification = backfill.verify();

        assertThat(verification.clean()).isFalse();
        assertThat(verification.missingObjects()).isEqualTo(1);
        assertThat(verification.hashMismatches()).isEqualTo(1);
        assertThat(verification.problems()).hasSize(2);
    }

    private Music saveLegacy(byte[] image, byte[] audio) {
        Music music = new Music();
        music.setTitle("legacy");
        music.setUser(owner);
        music.setStartingPrice(10_000L);
        music.setStatus(0);
        music.setAuctionEndTime(LocalDateTime.now().plusDays(3));
        music.setImage(image);
        music.setAudio(audio);
        music.setAuctionFailureEmailSent(false);
        music.setShowAllBids(false);
        music.setPopularComposer(false);
        music.setSteadyWorkComposer(false);
        music.setHitSongComposer(false);
        return musicRepository.save(music);
    }

    private Music reload(Music music) {
        return musicRepository.findById(music.getMusicUuid()).orElseThrow();
    }
}
