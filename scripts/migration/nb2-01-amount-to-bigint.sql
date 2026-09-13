-- ============================================================================
-- NB2 금액 계약 마이그레이션 — 금액 컬럼 DOUBLE → BIGINT (원 단위 정수)
--
-- 계약(코드의 com.notenest.payment.KrwAmounts 와 일치):
--   - 기준 통화: 원(KRW), 최소 단위 1원, 소수·배율(*100) 없음.
--   - 대상 컬럼: music.starting_price, music.current_highest_bid, bid.price, payment.price
--
-- 방식: 명시적 SQL(수동 적용). Flyway/Liquibase 미도입.
--       현재 ddl-auto=update 로 자동 반영되지만, 운영 전환 시에는 아래 순서로
--       preflight → 백업 → 변환 → 검증을 수동으로 수행하고 validate 로 굳힌다.
--
-- ★ 중요(중단 기준): STEP 1 preflight 가 한 행이라도 반환하면 여기서 멈춘다.
--   소수부가 있거나 원 단위로 무손실 표현할 수 없는 행은 임의 반올림·절삭·배율로
--   보정하지 않는다. 원래 의도한 금액을 복원할 수 없는 행은 제외 사유를 기록하고
--   사람이 판단한다. (payment.price 과거 행은 *100 배율이 섞여 있을 수 있음 — NB2
--   필수 범위 밖. 실데이터 변환은 대상·백업·범위가 확정된 경우에만 별도 수행.)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- STEP 1. preflight — 원 단위 정수로 무손실 변환 불가한 행 탐지 (결과가 있으면 중단)
-- ----------------------------------------------------------------------------
-- 소수부가 0이 아닌 행 (조용한 절삭 위험)
SELECT 'music.starting_price'      AS col, music_uuid AS pk, starting_price       AS val
  FROM music   WHERE starting_price       IS NOT NULL AND starting_price       <> FLOOR(starting_price)
UNION ALL
SELECT 'music.current_highest_bid', music_uuid, current_highest_bid
  FROM music   WHERE current_highest_bid  IS NOT NULL AND current_highest_bid  <> FLOOR(current_highest_bid)
UNION ALL
SELECT 'bid.price',                 bid_uuid,   price
  FROM bid     WHERE price               IS NOT NULL AND price               <> FLOOR(price)
UNION ALL
SELECT 'payment.price',             payment_uuid, price
  FROM payment WHERE price               IS NOT NULL AND price               <> FLOOR(price);

-- 범위 초과 행 (음수 또는 정합성 상한 100억원 초과) — KrwAmounts.MAX_WON 과 일치
SELECT 'music.starting_price'      AS col, music_uuid AS pk, starting_price       AS val
  FROM music   WHERE starting_price       IS NOT NULL AND (starting_price       < 0 OR starting_price       > 10000000000)
UNION ALL
SELECT 'music.current_highest_bid', music_uuid, current_highest_bid
  FROM music   WHERE current_highest_bid  IS NOT NULL AND (current_highest_bid  < 0 OR current_highest_bid  > 10000000000)
UNION ALL
SELECT 'bid.price',                 bid_uuid,   price
  FROM bid     WHERE price               IS NOT NULL AND (price               < 0 OR price               > 10000000000)
UNION ALL
SELECT 'payment.price',             payment_uuid, price
  FROM payment WHERE price               IS NOT NULL AND (price               < 0 OR price               > 10000000000);

-- ----------------------------------------------------------------------------
-- STEP 2. 백업 (변환 전 원본 보존 — 롤백/검증 기준)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS nb2_backup_music   AS SELECT music_uuid, starting_price, current_highest_bid FROM music;
CREATE TABLE IF NOT EXISTS nb2_backup_bid     AS SELECT bid_uuid, price FROM bid;
CREATE TABLE IF NOT EXISTS nb2_backup_payment AS SELECT payment_uuid, price FROM payment;

-- ----------------------------------------------------------------------------
-- STEP 3. 변환 — DOUBLE → BIGINT (무손실 행만 남아 있어야 한다; STEP 1 통과 전제)
-- ----------------------------------------------------------------------------
ALTER TABLE music   MODIFY COLUMN starting_price      BIGINT;
ALTER TABLE music   MODIFY COLUMN current_highest_bid BIGINT;
ALTER TABLE bid     MODIFY COLUMN price               BIGINT NOT NULL;
ALTER TABLE payment MODIFY COLUMN price               BIGINT NOT NULL;

-- ----------------------------------------------------------------------------
-- STEP 4. 검증 — 행 수 및 행별 금액이 백업(정수부)과 일치하는지 확인 (0건이어야 정상)
-- ----------------------------------------------------------------------------
SELECT 'music.starting_price'      AS col, m.music_uuid AS pk, b.starting_price AS before_val, m.starting_price AS after_val
  FROM music m JOIN nb2_backup_music b ON m.music_uuid = b.music_uuid
 WHERE NOT (m.starting_price <=> CAST(b.starting_price AS SIGNED))
UNION ALL
SELECT 'bid.price', m.bid_uuid, b.price, m.price
  FROM bid m JOIN nb2_backup_bid b ON m.bid_uuid = b.bid_uuid
 WHERE NOT (m.price <=> CAST(b.price AS SIGNED))
UNION ALL
SELECT 'payment.price', m.payment_uuid, b.price, m.price
  FROM payment m JOIN nb2_backup_payment b ON m.payment_uuid = b.payment_uuid
 WHERE NOT (m.price <=> CAST(b.price AS SIGNED));

-- 검증 통과 후 백업 정리(선택): DROP TABLE nb2_backup_music, nb2_backup_bid, nb2_backup_payment;
