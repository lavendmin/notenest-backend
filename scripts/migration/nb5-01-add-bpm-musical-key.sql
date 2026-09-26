-- ============================================================================
-- NB5 1단계 — 곡 BPM·조성(키) 컬럼 추가
--
-- 대상: music 테이블에 2컬럼 추가
--   bpm          INT          NULL  템포. 40~250 정수 (com.notenest.domain.Bpm)
--   musical_key  VARCHAR(20)  NULL  조성. 24개 enum 이름 (com.notenest.domain.MusicalKey, 예: A_MINOR)
--
-- 방식: 명시적 SQL(수동 적용). Flyway/Liquibase 미도입. 로컬은 ddl-auto=update 가 같은 컬럼을 만들 수 있으나
--       이 파일이 정본이다(타입·길이는 Music 엔티티 매핑과 일치). 네이티브 ENUM 대신 VARCHAR 를 쓴다 — 값 추가 시 ALTER 가 필요 없다.
--
-- 성격: 추가(additive) 전용. 기존 컬럼·데이터를 바꾸지 않는다.
--   - 두 컬럼 모두 NULL 허용: 기존 곡은 값이 없다(목록 필터가 있으면 제외, 상세 JSON 에서 필드 생략).
--   - CHECK 제약으로 앱 검증을 한 번 더 막는다. 앱 검증(400)이 1차이며 CHECK 는 직접 SQL 입력 등 우회 경로 방어다.
--     ddl-auto 로 만든 로컬·테스트 스키마에는 CHECK 가 없다(엔티티에 표현하지 않음) — 동작 차이는 없고 방어선만 다르다.
--   - IF NOT EXISTS 라 재실행해도 안전하다.
--   - 인덱스는 추가하지 않는다. 현재 목록 조회는 풀스캔 + filesort 이고(Phase 0 EXPLAIN), BPM·키 필터는 그 위의 조건일 뿐이다.
--
-- 롤백: 파일 끝 ROLLBACK 절(주석). 새 컬럼·제약만 제거한다.
-- 리허설: nb5-01-rehearsal.sql (격리 스키마에서 추가 → 검증 → 재실행 → 제약 위반 확인 → 롤백)
-- ============================================================================

-- STEP 1. preflight — 행 수, 이미 존재하는 NB5 컬럼
SELECT COUNT(*) AS music_rows FROM music;
SELECT COLUMN_NAME AS already_exists
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'music' AND COLUMN_NAME IN ('bpm', 'musical_key');

-- STEP 2. 컬럼 추가 (NULL 허용)
ALTER TABLE music
    ADD COLUMN IF NOT EXISTS bpm         INT         NULL,
    ADD COLUMN IF NOT EXISTS musical_key VARCHAR(20) NULL;

-- STEP 3. 값 제약 (재실행 안전)
ALTER TABLE music
    ADD CONSTRAINT IF NOT EXISTS chk_music_bpm_range CHECK (bpm IS NULL OR bpm BETWEEN 40 AND 250),
    ADD CONSTRAINT IF NOT EXISTS chk_music_musical_key CHECK (musical_key IS NULL OR musical_key IN (
        'C_MAJOR', 'D_FLAT_MAJOR', 'D_MAJOR', 'E_FLAT_MAJOR', 'E_MAJOR', 'F_MAJOR',
        'F_SHARP_MAJOR', 'G_MAJOR', 'A_FLAT_MAJOR', 'A_MAJOR', 'B_FLAT_MAJOR', 'B_MAJOR',
        'C_MINOR', 'C_SHARP_MINOR', 'D_MINOR', 'E_FLAT_MINOR', 'E_MINOR', 'F_MINOR',
        'F_SHARP_MINOR', 'G_MINOR', 'G_SHARP_MINOR', 'A_MINOR', 'B_FLAT_MINOR', 'B_MINOR'));

-- STEP 4. 검증 — 기대: new_columns = 2, nullable = 2, 기존 행의 값은 모두 NULL(backfill 없음), check_constraints = 2
SELECT COUNT(*) AS new_columns, SUM(IS_NULLABLE = 'YES') AS nullable
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'music' AND COLUMN_NAME IN ('bpm', 'musical_key');
SELECT COUNT(*) AS check_constraints
  FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS
 WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME IN ('chk_music_bpm_range', 'chk_music_musical_key');
SELECT COUNT(*) AS music_rows, SUM(bpm IS NOT NULL) AS with_bpm, SUM(musical_key IS NOT NULL) AS with_key FROM music;

-- ----------------------------------------------------------------------------
-- ROLLBACK (필요할 때만 수동 실행)
-- ALTER TABLE music DROP CONSTRAINT IF EXISTS chk_music_bpm_range, DROP CONSTRAINT IF EXISTS chk_music_musical_key;
-- ALTER TABLE music DROP COLUMN IF EXISTS bpm, DROP COLUMN IF EXISTS musical_key;
-- ----------------------------------------------------------------------------
