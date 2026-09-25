-- ============================================================================
-- NB1 저장 모델 전환 마지막 단계 — music.image / music.audio LOB 컬럼 삭제
--
-- 전제(모두 충족해야 실행):
--   - nb1-02 로 객체 키 컬럼 추가, 백필 완료, 대조 누락·크기·해시 불일치 0
--     (docs/verification/nb1-backfill.md, nb1-transition-gate.md)
--   - 애플리케이션 코드에서 image/audio 필드·fallback 을 제거한 버전으로 배포 준비됨
--   - **애플리케이션을 멈춘 상태**에서 실행 (옛 코드가 삭제된 컬럼을 읽거나 ddl-auto 가 되살리지 않도록)
--
-- 복구: 이 단계는 되돌릴 수 없다(컬럼·데이터 삭제). 원본 바이트는 S3 객체로 옮겨졌고 SHA-256 대조로 동일성을
--       확인했다. 대상이 성능 측정용 시드라 별도 DB 덤프·복구 시스템은 두지 않는다(운영 데이터라면 덤프를 먼저 뜬다).
--
-- 방식: 명시적 SQL(수동 적용). Flyway/Liquibase 미도입.
-- 리허설: nb1-03-rehearsal.sql
-- ============================================================================

-- ----------------------------------------------------------------------------
-- STEP 1. preflight — 현황
-- ----------------------------------------------------------------------------
SELECT COUNT(*)                                   AS music_rows,
       SUM(cover_object_key IS NOT NULL)          AS cover_keys,
       SUM(full_demo_object_key IS NOT NULL)      AS full_demo_keys,
       SUM(image IS NOT NULL AND cover_object_key IS NULL)     AS image_without_key,
       SUM(audio IS NOT NULL AND full_demo_object_key IS NULL) AS audio_without_key
  FROM music;

-- ----------------------------------------------------------------------------
-- STEP 2. 가드 — 객체 키 없이 LOB 만 남은 행이 하나라도 있으면 여기서 중단한다(삭제 = 데이터 유실).
-- ----------------------------------------------------------------------------
DELIMITER //
BEGIN NOT ATOMIC
    IF (SELECT COUNT(*) FROM music
         WHERE (image IS NOT NULL AND cover_object_key IS NULL)
            OR (audio IS NOT NULL AND full_demo_object_key IS NULL)) > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'nb1-03 중단: 객체 키 없이 LOB 만 있는 행이 있습니다. 백필·대조를 먼저 완료하세요.';
    END IF;
END //
DELIMITER ;

-- ----------------------------------------------------------------------------
-- STEP 3. 삭제
-- ----------------------------------------------------------------------------
ALTER TABLE music
    DROP COLUMN IF EXISTS image,
    DROP COLUMN IF EXISTS audio;

-- nb1-02 에서 LOB 불변 확인용으로 만든 스냅샷(행별 길이·MD5)도 더 이상 쓰지 않는다.
DROP TABLE IF EXISTS nb1_02_lob_snapshot;

-- ----------------------------------------------------------------------------
-- STEP 4. 검증
-- ----------------------------------------------------------------------------
-- (A) LOB 컬럼이 남아 있지 않다 (0행이어야 정상)
SELECT COLUMN_NAME
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'music' AND COLUMN_NAME IN ('image', 'audio');

-- (B) 행 수와 객체 키는 그대로다 (STEP 1 의 music_rows·cover_keys·full_demo_keys 와 같아야 정상)
SELECT COUNT(*) AS music_rows, SUM(cover_object_key IS NOT NULL) AS cover_keys, SUM(full_demo_object_key IS NOT NULL) AS full_demo_keys
  FROM music;

-- ----------------------------------------------------------------------------
-- STEP 5. 공간 회수 — 테이블 재구성
--   MariaDB 10.4+ 의 DROP COLUMN 은 기본이 INSTANT(메타데이터만 변경)라 LOB 가 쓰던 페이지가 테이블스페이스에
--   그대로 남는다. 재구성해야 파일이 줄고 삭제한 바이트가 물리적으로도 사라진다.
--   로컬 N=500 시드 실측: music.ibd 1,774,190,592B → 294,912B (재구성 약 1초, 행·키 불변).
-- ----------------------------------------------------------------------------
OPTIMIZE TABLE music;
SELECT ROUND(data_length / 1048576, 2) AS data_mib, ROUND(index_length / 1048576, 2) AS index_mib
  FROM INFORMATION_SCHEMA.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'music';
