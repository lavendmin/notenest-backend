-- ============================================================================
-- NB5 마이그레이션 리허설 (통제 데이터, 실제 MariaDB) — nb5-01-add-bpm-musical-key.sql 검증용
--
-- 격리 스키마(nb5_rehearsal)를 만들고 지운다. 기존 notenest·notenest_nb5 는 건드리지 않는다.
-- 실행: docker exec -i notenest-db mariadb -uroot -plocal-only -t < scripts/migration/nb5-01-rehearsal.sql
-- 기대: new_columns = 2, nullable = 2, check_constraints = 2, 기존 행 보존(rows_before = rows_after, 제목 변화 0),
--       재실행 후에도 컬럼·제약 수 동일, 제약 위반 INSERT 4건 모두 거부(accepted_invalid = 0), 정상 3건 저장(accepted_valid = 3), 롤백 후 컬럼 0.
-- ============================================================================
DROP DATABASE IF EXISTS nb5_rehearsal;
CREATE DATABASE nb5_rehearsal CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE nb5_rehearsal;

CREATE TABLE music (music_uuid VARCHAR(36) PRIMARY KEY, title VARCHAR(255));
INSERT INTO music VALUES ('m1', 'legacy one'), ('m2', 'legacy two'), ('m3', NULL);
CREATE TABLE snapshot AS SELECT music_uuid, title FROM music;

-- 본 스크립트 STEP 2·3 을 두 번 실행해 멱등성을 확인한다
ALTER TABLE music
    ADD COLUMN IF NOT EXISTS bpm         INT         NULL,
    ADD COLUMN IF NOT EXISTS musical_key VARCHAR(20) NULL;
ALTER TABLE music
    ADD CONSTRAINT IF NOT EXISTS chk_music_bpm_range CHECK (bpm IS NULL OR bpm BETWEEN 40 AND 250),
    ADD CONSTRAINT IF NOT EXISTS chk_music_musical_key CHECK (musical_key IS NULL OR musical_key IN (
        'C_MAJOR', 'D_FLAT_MAJOR', 'D_MAJOR', 'E_FLAT_MAJOR', 'E_MAJOR', 'F_MAJOR',
        'F_SHARP_MAJOR', 'G_MAJOR', 'A_FLAT_MAJOR', 'A_MAJOR', 'B_FLAT_MAJOR', 'B_MAJOR',
        'C_MINOR', 'C_SHARP_MINOR', 'D_MINOR', 'E_FLAT_MINOR', 'E_MINOR', 'F_MINOR',
        'F_SHARP_MINOR', 'G_MINOR', 'G_SHARP_MINOR', 'A_MINOR', 'B_FLAT_MINOR', 'B_MINOR'));
ALTER TABLE music
    ADD COLUMN IF NOT EXISTS bpm         INT         NULL,
    ADD COLUMN IF NOT EXISTS musical_key VARCHAR(20) NULL;
-- 두 번째 키 제약은 일부러 좁게(A_MINOR 만) 적는다 — 이미 있으면 덮어쓰지 않고 건너뛰는지 아래 C_MAJOR INSERT 로 확인한다.
ALTER TABLE music
    ADD CONSTRAINT IF NOT EXISTS chk_music_bpm_range CHECK (bpm IS NULL OR bpm BETWEEN 40 AND 250),
    ADD CONSTRAINT IF NOT EXISTS chk_music_musical_key CHECK (musical_key IS NULL OR musical_key IN ('A_MINOR'));

SELECT COUNT(*) AS new_columns, SUM(IS_NULLABLE = 'YES') AS nullable
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = 'nb5_rehearsal' AND TABLE_NAME = 'music' AND COLUMN_NAME IN ('bpm', 'musical_key');
SELECT COUNT(*) AS check_constraints
  FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS
 WHERE CONSTRAINT_SCHEMA = 'nb5_rehearsal' AND CONSTRAINT_NAME IN ('chk_music_bpm_range', 'chk_music_musical_key');
SELECT (SELECT COUNT(*) FROM snapshot) AS rows_before, (SELECT COUNT(*) FROM music) AS rows_after,
       (SELECT COUNT(*) FROM music m JOIN snapshot s USING (music_uuid) WHERE NOT (m.title <=> s.title)) AS title_changed,
       (SELECT SUM(bpm IS NOT NULL) + SUM(musical_key IS NOT NULL) FROM music) AS backfilled_values;

-- 정상 값은 들어가고, 제약 위반 4건은 거부된다(오류를 기록하고 계속)
INSERT INTO music VALUES ('ok1', 'valid', 40, 'A_MINOR'), ('ok2', 'valid', 250, NULL), ('ok3', 'first definition kept', 120, 'C_MAJOR');
DELIMITER //
CREATE PROCEDURE try_invalid()
BEGIN
    DECLARE accepted INT DEFAULT 0;
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION BEGIN END;
    INSERT INTO music VALUES ('bad1', 'bpm low', 39, NULL);
    INSERT INTO music VALUES ('bad2', 'bpm high', 251, NULL);
    INSERT INTO music VALUES ('bad3', 'display name', 90, 'Am');
    INSERT INTO music VALUES ('bad4', 'ambiguous', 90, 'AM');
    SELECT COUNT(*) INTO accepted FROM music WHERE music_uuid LIKE 'bad%';
    SELECT accepted AS accepted_invalid, (SELECT COUNT(*) FROM music WHERE music_uuid LIKE 'ok%') AS accepted_valid;
END //
DELIMITER ;
CALL try_invalid();

-- 롤백 절 실행 → 컬럼·제약 제거 확인
ALTER TABLE music DROP CONSTRAINT IF EXISTS chk_music_bpm_range, DROP CONSTRAINT IF EXISTS chk_music_musical_key;
ALTER TABLE music DROP COLUMN IF EXISTS bpm, DROP COLUMN IF EXISTS musical_key;
SELECT COUNT(*) AS columns_after_rollback
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = 'nb5_rehearsal' AND TABLE_NAME = 'music' AND COLUMN_NAME IN ('bpm', 'musical_key');

DROP DATABASE nb5_rehearsal;
