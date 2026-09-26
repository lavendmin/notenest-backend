-- ============================================================================
-- NB5 마이그레이션 리허설 (통제 데이터, 실제 MariaDB) — nb5-01-add-bpm-musical-key.sql(P0, 컬럼 추가만) 검증용
--
-- 격리 스키마(nb5_rehearsal)를 만들고 지운다. 기존 notenest·notenest_nb5 는 건드리지 않는다.
-- 실행: docker exec -i notenest-db mariadb -uroot -plocal-only -t < scripts/migration/nb5-01-rehearsal.sql
-- 기대: new_columns = 2, nullable = 2, 기존 행 보존(rows_before = rows_after, 제목 변화 0, backfill 0),
--       재실행 후에도 컬럼 수 동일, 롤백 후 컬럼 0.
-- DB CHECK 제약은 P1 로 분리했다(docs/nb5/p1-db-check-constraints.md) — 이 리허설 대상이 아니다.
-- ============================================================================
DROP DATABASE IF EXISTS nb5_rehearsal;
CREATE DATABASE nb5_rehearsal CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE nb5_rehearsal;

CREATE TABLE music (music_uuid VARCHAR(36) PRIMARY KEY, title VARCHAR(255));
INSERT INTO music VALUES ('m1', 'legacy one'), ('m2', 'legacy two'), ('m3', NULL);
CREATE TABLE snapshot AS SELECT music_uuid, title FROM music;

-- 본 스크립트 STEP 2 를 두 번 실행해 멱등성을 확인한다
ALTER TABLE music
    ADD COLUMN IF NOT EXISTS bpm         INT         NULL,
    ADD COLUMN IF NOT EXISTS musical_key VARCHAR(20) NULL;
ALTER TABLE music
    ADD COLUMN IF NOT EXISTS bpm         INT         NULL,
    ADD COLUMN IF NOT EXISTS musical_key VARCHAR(20) NULL;

SELECT COUNT(*) AS new_columns, SUM(IS_NULLABLE = 'YES') AS nullable
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = 'nb5_rehearsal' AND TABLE_NAME = 'music' AND COLUMN_NAME IN ('bpm', 'musical_key');
SELECT (SELECT COUNT(*) FROM snapshot) AS rows_before, (SELECT COUNT(*) FROM music) AS rows_after,
       (SELECT COUNT(*) FROM music m JOIN snapshot s USING (music_uuid) WHERE NOT (m.title <=> s.title)) AS title_changed,
       (SELECT SUM(bpm IS NOT NULL) + SUM(musical_key IS NOT NULL) FROM music) AS backfilled_values;

-- 롤백 절 실행 → 컬럼 제거 확인
ALTER TABLE music DROP COLUMN IF EXISTS bpm, DROP COLUMN IF EXISTS musical_key;
SELECT COUNT(*) AS columns_after_rollback
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = 'nb5_rehearsal' AND TABLE_NAME = 'music' AND COLUMN_NAME IN ('bpm', 'musical_key');

DROP DATABASE nb5_rehearsal;
