-- ============================================================================
-- NB1 저장 모델 전환 1단계 — 곡 미디어 객체 키·메타데이터 컬럼 추가
--
-- 대상: music 테이블에 자산 3종(커버·미리듣기·전체 데모) × 4컬럼 = 12컬럼 추가
--   {asset}_object_key     VARCHAR(512)  S3 객체 키 (예: music/{musicUuid}/full-demo/{assetUuid})
--   {asset}_content_type   VARCHAR(100)  시그니처로 판별한 실제 형식 (image/jpeg·image/png·audio/mpeg·audio/wav)
--   {asset}_size           BIGINT        바이트 크기
--   {asset}_original_name  VARCHAR(255)  업로드 원본 파일명 (다운로드 파일명·확장자 결정용)
--   asset = cover | preview | full_demo
--
-- 방식: 명시적 SQL(수동 적용). Flyway/Liquibase 미도입. 로컬은 ddl-auto=update 가 같은 컬럼을 만들 수 있으나
--       이 파일이 정본이다(타입·길이는 Music 엔티티 매핑과 일치).
--
-- 성격: 추가(additive) 전용이다. 기존 컬럼·데이터를 바꾸지 않는다.
--   - 새 컬럼은 모두 NULL 허용: 기존 행은 백필 전까지 키가 없고, 기존 곡은 미리듣기가 영구히 없을 수 있다.
--     신규 곡의 미리듣기 필수는 스키마가 아니라 등록 API 검증으로 집행한다.
--   - image / audio LOB 컬럼은 삭제하지 않는다. 백필·대조가 끝날 때까지 읽기 fallback 으로 남긴다(삭제는 마지막 마이그레이션).
--   - ADD COLUMN IF NOT EXISTS 라 재실행해도 안전하다.
--
-- 롤백: 파일 끝 ROLLBACK 절(주석). 새 컬럼만 제거하며 LOB 는 영향 없음.
-- 리허설: nb1-02-rehearsal.sql (격리 스키마에서 추가 → 검증 → 재실행 → 롤백을 재현)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- STEP 1. preflight — 현황 기록 (행 수, 이미 존재하는 NB1 컬럼)
-- ----------------------------------------------------------------------------
SELECT COUNT(*) AS music_rows FROM music;

SELECT COLUMN_NAME AS already_exists
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'music'
   AND (COLUMN_NAME LIKE 'cover\_%' OR COLUMN_NAME LIKE 'preview\_%' OR COLUMN_NAME LIKE 'full\_demo\_%');

-- ----------------------------------------------------------------------------
-- STEP 2. 스냅샷 — 기존 LOB 가 변하지 않았음을 확인할 기준 (행별 길이·MD5)
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS nb1_02_lob_snapshot;
CREATE TABLE nb1_02_lob_snapshot AS
SELECT music_uuid, LENGTH(image) AS image_len, MD5(image) AS image_md5, LENGTH(audio) AS audio_len, MD5(audio) AS audio_md5
  FROM music;

-- ----------------------------------------------------------------------------
-- STEP 3. 컬럼 추가 (모두 NULL 허용)
-- ----------------------------------------------------------------------------
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

-- ----------------------------------------------------------------------------
-- STEP 4. 검증 — 아래 SELECT 는 결과 해석 기준을 주석으로 적었다.
-- ----------------------------------------------------------------------------
-- (A) 새 컬럼 12개가 기대 타입·NULL 허용으로 존재 (12행, 모두 is_nullable=YES)
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'music'
   AND (COLUMN_NAME LIKE 'cover\_%' OR COLUMN_NAME LIKE 'preview\_%' OR COLUMN_NAME LIKE 'full\_demo\_%')
 ORDER BY ORDINAL_POSITION;

-- (B) 행 수 불변 (0건이어야 정상)
SELECT (SELECT COUNT(*) FROM music) AS cur, (SELECT COUNT(*) FROM nb1_02_lob_snapshot) AS snap
 WHERE (SELECT COUNT(*) FROM music) <> (SELECT COUNT(*) FROM nb1_02_lob_snapshot);

-- (C) 기존 LOB 불변 — 길이·MD5 불일치 행 (0건이어야 정상)
SELECT m.music_uuid
  FROM music m JOIN nb1_02_lob_snapshot s ON m.music_uuid = s.music_uuid
 WHERE NOT (LENGTH(m.image) <=> s.image_len) OR NOT (MD5(m.image) <=> s.image_md5)
    OR NOT (LENGTH(m.audio) <=> s.audio_len) OR NOT (MD5(m.audio) <=> s.audio_md5);

-- (D) 새 컬럼은 아직 비어 있다 — 키가 채워진 행 (0건이어야 정상, 백필 전)
SELECT COUNT(*) AS rows_with_keys
  FROM music
 WHERE cover_object_key IS NOT NULL OR preview_object_key IS NOT NULL OR full_demo_object_key IS NOT NULL;

-- 검증 통과 후 스냅샷 정리(선택): DROP TABLE nb1_02_lob_snapshot;

-- ----------------------------------------------------------------------------
-- ROLLBACK (필요할 때만 수동 실행) — 새 컬럼만 제거, LOB·기존 컬럼은 영향 없음
-- ----------------------------------------------------------------------------
-- ALTER TABLE music
--     DROP COLUMN IF EXISTS cover_object_key,     DROP COLUMN IF EXISTS cover_content_type,
--     DROP COLUMN IF EXISTS cover_size,           DROP COLUMN IF EXISTS cover_original_name,
--     DROP COLUMN IF EXISTS preview_object_key,   DROP COLUMN IF EXISTS preview_content_type,
--     DROP COLUMN IF EXISTS preview_size,         DROP COLUMN IF EXISTS preview_original_name,
--     DROP COLUMN IF EXISTS full_demo_object_key, DROP COLUMN IF EXISTS full_demo_content_type,
--     DROP COLUMN IF EXISTS full_demo_size,       DROP COLUMN IF EXISTS full_demo_original_name;
