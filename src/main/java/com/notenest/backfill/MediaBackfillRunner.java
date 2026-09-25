package com.notenest.backfill;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 백필 실행 진입점 — backfill 프로파일에서만 뜬다(웹 서버·경매 스케줄러 없이 실행 후 종료).
 *
 * 실행(레포 루트, 환경변수 AWS_* · NOTENEST_S3_BUCKET 설정 후):
 *   ./gradlew bootRun --args='--spring.profiles.active=local,backfill'              # 백필 → 대조
 *   ./gradlew bootRun --args='--spring.profiles.active=local,backfill --verify-only' # 대조만
 *
 * 백필이 실패한 곡이 있거나 대조에서 누락·불일치가 나오면 예외로 끝나 종료 코드가 0 이 아니다.
 * 몇 번을 다시 실행해도 안전하다(결정적 키·이전된 자산 건너뛰기·같은 객체 재사용).
 */
@Component
@Profile("backfill")
public class MediaBackfillRunner implements ApplicationRunner {

    private final MediaBackfillService backfillService;

    public MediaBackfillRunner(MediaBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("verify-only")) {
            MediaBackfillService.BackfillReport report = backfillService.backfill();
            if (report.failedSongs() > 0) {
                throw new IllegalStateException("백필 실패 곡 " + report.failedSongs() + "건 — 로그 확인 후 다시 실행하세요.");
            }
        }
        MediaBackfillService.VerificationReport verification = backfillService.verify();
        if (!verification.clean()) {
            throw new IllegalStateException("대조 불일치 — missingKeys=" + verification.missingKeys()
                    + " missingObjects=" + verification.missingObjects()
                    + " sizeMismatches=" + verification.sizeMismatches()
                    + " hashMismatches=" + verification.hashMismatches());
        }
    }
}
