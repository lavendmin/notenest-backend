-- ============================================================================
-- NB1 마이그레이션 리허설 (통제 데이터, 실제 MariaDB) — nb1-03-drop-media-lob-columns.sql 검증용
--
-- 목적: 격리된 임시 스키마(nb1_03_rehearsal)에서
--   preflight 가 "키 없이 LOB 만 있는 행"을 실제로 잡는지 → 그 행을 정리한 뒤 가드 통과 → 컬럼 삭제 →
--   행·키 보존 → 재실행(IF EXISTS) 무오류를 재현한다.
--   가드의 SIGNAL 자체는 이 스크립트를 멈추므로 여기서 발동시키지 않고, 가드와 같은 조건식의 카운트로 탐지를 증명한다.
-- 실행: docker exec -i notenest-db mariadb -uroot -plocal-only < scripts/migration/nb1-03-rehearsal.sql
-- 기대: PREFLIGHT_CATCHES_LOB_WITHOUT_KEY = 1, LOB_COLUMNS_AFTER_DROP = 0, ROWS_AFTER = 2, KEYS_AFTER = 2,
--       SNAPSHOT_TABLES_AFTER = 0, ROWS_AFTER_OPTIMIZE = 2, 나머지 n = 0
-- ============================================================================
DROP DATABASE IF EXISTS nb1_03_rehearsal;
CREATE DATABASE nb1_03_rehearsal;
USE nb1_03_rehearsal;

CREATE TABLE music (
    music_uuid VARCHAR(36) PRIMARY KEY,
    image LONGBLOB, audio LONGBLOB,
    cover_object_key VARCHAR(512), full_demo_object_key VARCHAR(512)
);
CREATE TABLE nb1_02_lob_snapshot (music_uuid VARCHAR(36));
INSERT INTO music VALUES
  ('m1', REPEAT('i', 16), REPEAT('a', 64), 'music/m1/cover/legacy', 'music/m1/full-demo/legacy'),
  ('m2', NULL, NULL, 'music/m2/cover/x', 'music/m2/full-demo/y'),
  ('bad', REPEAT('i', 16), REPEAT('a', 64), NULL, NULL);  -- 백필 누락 행

-- preflight 가 누락 행을 실제로 잡는다(가드와 같은 조건식)
SELECT 'PREFLIGHT_CATCHES_LOB_WITHOUT_KEY' AS chk, COUNT(*) AS n FROM music
 WHERE (image IS NOT NULL AND cover_object_key IS NULL) OR (audio IS NOT NULL AND full_demo_object_key IS NULL);

-- 누락 행을 정리(실제 절차에서는 백필 재실행으로 키를 채운다)한 뒤 가드 통과
DELETE FROM music WHERE music_uuid = 'bad';
DELIMITER //
BEGIN NOT ATOMIC
    IF (SELECT COUNT(*) FROM music
         WHERE (image IS NOT NULL AND cover_object_key IS NULL)
            OR (audio IS NOT NULL AND full_demo_object_key IS NULL)) > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'nb1-03 중단';
    END IF;
END //
DELIMITER ;
SELECT 'GUARD_PASSED' AS chk, 0 AS n;

-- 삭제 — 두 번 실행해도 오류가 없어야 한다(IF EXISTS)
ALTER TABLE music DROP COLUMN IF EXISTS image, DROP COLUMN IF EXISTS audio;
ALTER TABLE music DROP COLUMN IF EXISTS image, DROP COLUMN IF EXISTS audio;
DROP TABLE IF EXISTS nb1_02_lob_snapshot;

SELECT 'LOB_COLUMNS_AFTER_DROP' AS chk, COUNT(*) AS n FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = 'nb1_03_rehearsal' AND TABLE_NAME = 'music' AND COLUMN_NAME IN ('image', 'audio');
SELECT 'ROWS_AFTER' AS chk, COUNT(*) AS n FROM music;
SELECT 'KEYS_AFTER' AS chk, SUM(cover_object_key IS NOT NULL AND full_demo_object_key IS NOT NULL) AS n FROM music;
SELECT 'SNAPSHOT_TABLES_AFTER' AS chk, COUNT(*) AS n FROM INFORMATION_SCHEMA.TABLES
 WHERE TABLE_SCHEMA = 'nb1_03_rehearsal' AND TABLE_NAME = 'nb1_02_lob_snapshot';

-- 공간 회수(재구성) 후에도 행·키 보존
OPTIMIZE TABLE music;
SELECT 'ROWS_AFTER_OPTIMIZE' AS chk, COUNT(*) AS n FROM music;

DROP DATABASE nb1_03_rehearsal;
