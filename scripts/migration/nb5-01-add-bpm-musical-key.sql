-- ============================================================================
-- NB5 1단계 — 곡 BPM·조성(키) 컬럼 추가 (P0)
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
--   - 값의 범위·형식은 애플리케이션이 집행한다(P0): 등록·수정·목록 필터에서 BPM 40~250 정수, 24개 키만 받고 나머지는 400.
--   - DB CHECK 제약은 이 파일에 없다 — P1 로 분리했다(docs/nb5/p1-db-check-constraints.md). 이 로컬 환경에서 테이블
--     재구성 ALTER 가 errno 194 로 실패해 실제 스키마 적용을 확인하지 못했기 때문이다(2026-09-26 리뷰 반영).
--   - IF NOT EXISTS 라 재실행해도 안전하다. 컬럼 추가는 MariaDB 10.11 에서 instant ALTER 라 테이블을 재구성하지 않는다.
--   - 인덱스는 추가하지 않는다. 검색어 경로는 Elasticsearch, 목록 필터는 풀스캔 + filesort 위의 조건일 뿐이다.
--
-- 롤백: 파일 끝 ROLLBACK 절(주석). 새 컬럼만 제거한다.
-- 리허설: nb5-01-rehearsal.sql (격리 스키마에서 추가 → 검증 → 재실행 → 기존 행 보존 → 롤백)
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

-- STEP 3. 검증 — 기대: new_columns = 2, nullable = 2, 기존 행의 값은 모두 NULL(backfill 없음)
SELECT COUNT(*) AS new_columns, SUM(IS_NULLABLE = 'YES') AS nullable
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'music' AND COLUMN_NAME IN ('bpm', 'musical_key');
SELECT COUNT(*) AS music_rows, SUM(bpm IS NOT NULL) AS with_bpm, SUM(musical_key IS NOT NULL) AS with_key FROM music;

-- ----------------------------------------------------------------------------
-- ROLLBACK (필요할 때만 수동 실행)
-- ALTER TABLE music DROP COLUMN IF EXISTS bpm, DROP COLUMN IF EXISTS musical_key;
-- ----------------------------------------------------------------------------
