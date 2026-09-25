-- ============================================================================
-- NB1 마이그레이션 리허설 (통제 데이터, 실제 MariaDB) — nb1-02-add-media-object-columns.sql 검증용
--
-- 목적: 격리된 임시 스키마(nb1_rehearsal)에서 통제 픽스처로
--   스냅샷 → 컬럼 추가 → 검증 → 재실행(멱등) → 롤백 → 원상 확인을 결정적으로 재현한다.
-- 실행: docker exec -i notenest-db mariadb -uroot -plocal-only < scripts/migration/nb1-02-rehearsal.sql
-- 기대: NEW_COLUMNS = 12, NULLABLE_NEW_COLUMNS = 12, VERIFY_CATCHES_LOB_CHANGE = 1(변경을 실제로 탐지), 나머지 chk 의 n = 0.
-- ============================================================================
DROP DATABASE IF EXISTS nb1_rehearsal;
CREATE DATABASE nb1_rehearsal;
USE nb1_rehearsal;

-- 운영 music 의 관련 열만 축약한 통제 테이블. image/audio 는 LONGBLOB, NULL 행 포함.
CREATE TABLE music (music_uuid VARCHAR(36) PRIMARY KEY, title VARCHAR(255), image LONGBLOB, audio LONGBLOB);
INSERT INTO music VALUES
  ('m1', 'with media',  REPEAT('i', 1024), REPEAT('a', 4096)),
  ('m2', 'binary',      UNHEX('FFD8FFE000104A464946'), UNHEX('494433040000000000')),
  ('m3', 'null media',  NULL, NULL);

-- STEP2 스냅샷 (본 스크립트와 동일)
CREATE TABLE nb1_02_lob_snapshot AS
SELECT music_uuid, LENGTH(image) AS image_len, MD5(image) AS image_md5, LENGTH(audio) AS audio_len, MD5(audio) AS audio_md5
  FROM music;

-- STEP3 컬럼 추가 — 본 스크립트와 동일한 문장을 두 번 실행해 멱등성을 확인한다
ALTER TABLE music
    ADD COLUMN IF NOT EXISTS cover_object_key         VARCHAR(512) NULL,
    ADD COLUMN IF NOT EXISTS cover_content_type       VARCHAR(100) NULL,
    ADD COLUMN IF NOT EXISTS cover_size               BIGINT       NULL,
    ADD COLUMN IF NOT EXISTS cover_original_name      VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS preview_object_key       VARCHAR(512) NULL,
    ADD COLUMN IF NOT EXISTS preview_content_type     VARCHAR(100) NULL,
    ADD COLUMN IF NOT EXISTS preview_size             BIGINT       NULL,
    ADD COLUMN IF NOT EXISTS preview_original_name    VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS full_demo_object_key     VARCHAR(512) NULL,
    ADD COLUMN IF NOT EXISTS full_demo_content_type   VARCHAR(100) NULL,
    ADD COLUMN IF NOT EXISTS full_demo_size           BIGINT       NULL,
    ADD COLUMN IF NOT EXISTS full_demo_original_name  VARCHAR(255) NULL;
ALTER TABLE music
    ADD COLUMN IF NOT EXISTS cover_object_key         VARCHAR(512) NULL,
    ADD COLUMN IF NOT EXISTS cover_content_type       VARCHAR(100) NULL,
    ADD COLUMN IF NOT EXISTS cover_size               BIGINT       NULL,
    ADD COLUMN IF NOT EXISTS cover_original_name      VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS preview_object_key       VARCHAR(512) NULL,
    ADD COLUMN IF NOT EXISTS preview_content_type     VARCHAR(100) NULL,
    ADD COLUMN IF NOT EXISTS preview_size             BIGINT       NULL,
    ADD COLUMN IF NOT EXISTS preview_original_name    VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS full_demo_object_key     VARCHAR(512) NULL,
    ADD COLUMN IF NOT EXISTS full_demo_content_type   VARCHAR(100) NULL,
    ADD COLUMN IF NOT EXISTS full_demo_size           BIGINT       NULL,
    ADD COLUMN IF NOT EXISTS full_demo_original_name  VARCHAR(255) NULL;

-- STEP4 검증
SELECT 'NEW_COLUMNS' AS chk, COUNT(*) AS n
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = 'nb1_rehearsal' AND TABLE_NAME = 'music'
   AND (COLUMN_NAME LIKE 'cover\_%' OR COLUMN_NAME LIKE 'preview\_%' OR COLUMN_NAME LIKE 'full\_demo\_%');
SELECT 'NULLABLE_NEW_COLUMNS' AS chk, COUNT(*) AS n
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = 'nb1_rehearsal' AND TABLE_NAME = 'music' AND IS_NULLABLE = 'YES'
   AND (COLUMN_NAME LIKE 'cover\_%' OR COLUMN_NAME LIKE 'preview\_%' OR COLUMN_NAME LIKE 'full\_demo\_%');
SELECT 'VERIFY_ROWCOUNT_DIFF' AS chk, ((SELECT COUNT(*) FROM music) <> (SELECT COUNT(*) FROM nb1_02_lob_snapshot)) AS n;
SELECT 'VERIFY_LOB_CHANGED' AS chk, COUNT(*) AS n
  FROM music m JOIN nb1_02_lob_snapshot s ON m.music_uuid = s.music_uuid
 WHERE NOT (LENGTH(m.image) <=> s.image_len) OR NOT (MD5(m.image) <=> s.image_md5)
    OR NOT (LENGTH(m.audio) <=> s.audio_len) OR NOT (MD5(m.audio) <=> s.audio_md5);
SELECT 'VERIFY_KEYS_PREFILLED' AS chk, COUNT(*) AS n
  FROM music
 WHERE cover_object_key IS NOT NULL OR preview_object_key IS NOT NULL OR full_demo_object_key IS NOT NULL;

-- 검증 쿼리가 LOB 변경을 실제로 잡는지 증명: 한 행을 바꿔 탐지 1 → 원복
UPDATE music SET audio = REPEAT('b', 4096) WHERE music_uuid = 'm1';
SELECT 'VERIFY_CATCHES_LOB_CHANGE' AS chk, COUNT(*) AS n
  FROM music m JOIN nb1_02_lob_snapshot s ON m.music_uuid = s.music_uuid
 WHERE NOT (MD5(m.audio) <=> s.audio_md5);
UPDATE music SET audio = REPEAT('a', 4096) WHERE music_uuid = 'm1';

-- ROLLBACK 리허설 — 새 컬럼만 제거하고 LOB·행이 스냅샷과 같은지 확인
ALTER TABLE music
    DROP COLUMN IF EXISTS cover_object_key,     DROP COLUMN IF EXISTS cover_content_type,
    DROP COLUMN IF EXISTS cover_size,           DROP COLUMN IF EXISTS cover_original_name,
    DROP COLUMN IF EXISTS preview_object_key,   DROP COLUMN IF EXISTS preview_content_type,
    DROP COLUMN IF EXISTS preview_size,         DROP COLUMN IF EXISTS preview_original_name,
    DROP COLUMN IF EXISTS full_demo_object_key, DROP COLUMN IF EXISTS full_demo_content_type,
    DROP COLUMN IF EXISTS full_demo_size,       DROP COLUMN IF EXISTS full_demo_original_name;
SELECT 'NEW_COLUMNS_AFTER_ROLLBACK' AS chk, COUNT(*) AS n
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = 'nb1_rehearsal' AND TABLE_NAME = 'music'
   AND (COLUMN_NAME LIKE 'cover\_%' OR COLUMN_NAME LIKE 'preview\_%' OR COLUMN_NAME LIKE 'full\_demo\_%');
SELECT 'ROLLBACK_LOB_CHANGED' AS chk, COUNT(*) AS n
  FROM music m JOIN nb1_02_lob_snapshot s ON m.music_uuid = s.music_uuid
 WHERE NOT (MD5(m.image) <=> s.image_md5) OR NOT (MD5(m.audio) <=> s.audio_md5);

DROP DATABASE nb1_rehearsal;
