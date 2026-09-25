package com.notenest.backfill;

import com.notenest.domain.MediaObject;
import com.notenest.domain.Music;
import com.notenest.repository.MusicRepository;
import com.notenest.storage.MediaAssetType;
import com.notenest.storage.MediaAssetType.DetectedMedia;
import com.notenest.storage.MediaUploadValidator;
import com.notenest.storage.ObjectStorage;
import com.notenest.storage.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 기존 곡의 LOB(image·audio)를 객체 저장소로 옮기는 백필과 그 대조.
 *
 * 범위: 기존 곡은 NA1·NA2 측정용 시드라서 운영 이관이 아니라 저장 구조 전환 리허설이다.
 * 별도 복구 시스템은 두지 않는다. LOB 는 지우지 않으므로, 백필이 잘못돼도 원본은 DB 에 남아 있다.
 *
 * - 결정적 키: music/{musicUuid}/cover/legacy, music/{musicUuid}/full-demo/legacy — 재실행해도 같은 키라 중복 객체가 생기지 않는다.
 * - 이미 이전된 자산(DB 에 키가 있음)은 건너뛴다.
 * - 중단 후 재실행: 업로드는 됐는데 DB 갱신 전에 멈춘 경우, 같은 키의 객체가 크기·SHA-256 까지 같으면 다시 올리지 않고 재사용한다.
 * - 곡 하나가 실패해도 다음 곡으로 넘어가고 실패 건수를 보고한다(재실행하면 그 곡만 다시 처리된다).
 * - 기존 audio 는 전체 데모로 옮긴다. 미리듣기는 만들지 않는다(기존 곡의 previewObjectKey 는 null).
 * - Content-Type 은 LOB 시그니처로 판별하고, 판별되지 않으면(시드 더미 등) application/octet-stream 으로 기록한다.
 */
@Service
public class MediaBackfillService {

    private static final Logger log = LoggerFactory.getLogger(MediaBackfillService.class);
    static final String UNKNOWN_CONTENT_TYPE = "application/octet-stream";

    private final MusicRepository musicRepository;
    private final ObjectStorage storage;
    private final TransactionTemplate transactionTemplate;

    public MediaBackfillService(MusicRepository musicRepository, ObjectStorage storage, TransactionTemplate transactionTemplate) {
        this.musicRepository = musicRepository;
        this.storage = storage;
        this.transactionTemplate = transactionTemplate;
    }

    public record BackfillReport(int candidates, int uploaded, int reused, int migratedSongs, int failedSongs, List<UUID> failed) {
    }

    public record VerificationReport(int songs, int checkedObjects, int missingKeys, int missingObjects,
                                     int sizeMismatches, int hashMismatches, List<String> problems) {
        public boolean clean() {
            return missingKeys == 0 && missingObjects == 0 && sizeMismatches == 0 && hashMismatches == 0;
        }
    }

    /** 결정적 백필 키. */
    public static String legacyKey(UUID musicUuid, MediaAssetType type) {
        return "music/" + musicUuid + "/" + type.keySegment() + "/legacy";
    }

    public BackfillReport backfill() {
        List<UUID> candidates = musicRepository.findUuidsNeedingMediaBackfill();
        int[] counts = new int[3]; // uploaded, reused, migratedSongs
        List<UUID> failed = new ArrayList<>();
        for (UUID musicUuid : candidates) {
            try {
                // 곡마다 한 트랜잭션 — LOB 는 한 곡 분량만 메모리에 올린다.
                transactionTemplate.executeWithoutResult(tx -> migrate(musicUuid, counts));
                counts[2]++;
            } catch (RuntimeException e) {
                failed.add(musicUuid);
                log.warn("[BACKFILL] 실패 music={} — 재실행하면 이 곡만 다시 처리된다", musicUuid, e);
            }
        }
        BackfillReport report = new BackfillReport(candidates.size(), counts[0], counts[1], counts[2], failed.size(), failed);
        log.info("[BACKFILL] candidates={} uploaded={} reused={} migratedSongs={} failedSongs={}",
                report.candidates(), report.uploaded(), report.reused(), report.migratedSongs(), report.failedSongs());
        return report;
    }

    private void migrate(UUID musicUuid, int[] counts) {
        Music music = musicRepository.findById(musicUuid).orElseThrow();
        if (music.getCover() == null && music.getImage() != null) {
            music.setCover(moveLob(musicUuid, MediaAssetType.COVER, music.getImage(), counts));
        }
        if (music.getFullDemo() == null && music.getAudio() != null) {
            music.setFullDemo(moveLob(musicUuid, MediaAssetType.FULL_DEMO, music.getAudio(), counts));
        }
        // LOB 는 지우지 않는다 — 대조가 끝나고 마지막 마이그레이션에서 컬럼째 삭제한다.
    }

    private MediaObject moveLob(UUID musicUuid, MediaAssetType type, byte[] lob, int[] counts) {
        String key = legacyKey(musicUuid, type);
        String contentType = MediaUploadValidator.detect(Arrays.copyOf(lob, Math.min(lob.length, 12)))
                .map(DetectedMedia::contentType)
                .orElse(UNKNOWN_CONTENT_TYPE);
        String sha256 = sha256Base64(lob);

        Optional<StoredObject> existing = storage.head(key);
        if (existing.isPresent() && existing.get().size() == lob.length && sha256.equals(existing.get().sha256Base64())) {
            counts[1]++; // 이전 실행에서 올라갔지만 DB 갱신 전에 멈춘 객체 — 그대로 쓴다
        } else {
            storage.put(key, new ByteArrayInputStream(lob), lob.length, contentType);
            counts[0]++;
        }
        return new MediaObject(key, contentType, (long) lob.length, null);
    }

    /**
     * 대조: 모든 곡에 대해
     *  - LOB 가 있는데 키가 없는 자산 → 누락(missingKeys)
     *  - 키가 있는데 객체가 없음 → missingObjects
     *  - 객체 크기가 DB 기록과 다름 → sizeMismatches
     *  - LOB 가 남아 있으면 LOB 의 SHA-256 과 객체 체크섬이 다름 → hashMismatches
     */
    public VerificationReport verify() {
        List<UUID> all = musicRepository.findAllUuids();
        int[] c = new int[5]; // checkedObjects, missingKeys, missingObjects, sizeMismatches, hashMismatches
        List<String> problems = new ArrayList<>();
        for (UUID musicUuid : all) {
            transactionTemplate.executeWithoutResult(tx -> {
                Music music = musicRepository.findById(musicUuid).orElseThrow();
                check(musicUuid, MediaAssetType.COVER, music.getCover(), music.getImage(), c, problems);
                check(musicUuid, MediaAssetType.PREVIEW, music.getPreview(), null, c, problems);
                check(musicUuid, MediaAssetType.FULL_DEMO, music.getFullDemo(), music.getAudio(), c, problems);
            });
        }
        VerificationReport report = new VerificationReport(all.size(), c[0], c[1], c[2], c[3], c[4], problems);
        log.info("[BACKFILL-VERIFY] songs={} checkedObjects={} missingKeys={} missingObjects={} sizeMismatches={} hashMismatches={}",
                report.songs(), report.checkedObjects(), report.missingKeys(), report.missingObjects(),
                report.sizeMismatches(), report.hashMismatches());
        problems.forEach(p -> log.warn("[BACKFILL-VERIFY] {}", p));
        return report;
    }

    private void check(UUID musicUuid, MediaAssetType type, MediaObject media, byte[] lob,
                       int[] c, List<String> problems) {
        if (media == null || media.getObjectKey() == null) {
            if (lob != null) {
                c[1]++;
                problems.add("missingKey music=" + musicUuid + " asset=" + type);
            }
            return;
        }
        c[0]++;
        Optional<StoredObject> head = storage.head(media.getObjectKey());
        if (head.isEmpty()) {
            c[2]++;
            problems.add("missingObject key=" + media.getObjectKey());
            return;
        }
        if (media.getSize() == null || head.get().size() != media.getSize()) {
            c[3]++;
            problems.add("sizeMismatch key=" + media.getObjectKey() + " db=" + media.getSize() + " object=" + head.get().size());
        }
        if (lob != null && !sha256Base64(lob).equals(head.get().sha256Base64())) {
            c[4]++;
            problems.add("hashMismatch key=" + media.getObjectKey());
        }
    }

    static String sha256Base64(byte[] bytes) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
