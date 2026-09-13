-- ============================================================================
-- NB2 마이그레이션 리허설 (통제 데이터, 실제 MariaDB) — nb2-01-amount-to-bigint.sql 검증용
--
-- 목적: 격리된 임시 스키마(nb2_rehearsal)에서 payment 행을 포함한 통제 픽스처로
--   preflight → 백업 → DOUBLE→BIGINT → 전후 보존 검증(행수·PK대칭·값) → 복구(롤백) 리허설을
--   결정적으로 재현한다. 실데이터가 아니라 단위가 명확한 통제 데이터다.
-- 실행: docker exec -i notenest-db mariadb -uroot -plocal-only < scripts/migration/nb2-01-rehearsal.sql
-- 기대: 모든 chk 의 n = 0, 단 PREFLIGHT_CATCHES_FRACTION = 1 (소수 행을 실제로 탐지).
-- ============================================================================
DROP DATABASE IF EXISTS nb2_rehearsal;
CREATE DATABASE nb2_rehearsal;
USE nb2_rehearsal;

CREATE TABLE music   (music_uuid VARCHAR(36) PRIMARY KEY, starting_price DOUBLE, current_highest_bid DOUBLE);
CREATE TABLE bid     (bid_uuid VARCHAR(36) PRIMARY KEY, price DOUBLE);
CREATE TABLE payment (payment_uuid VARCHAR(36) PRIMARY KEY, price DOUBLE);

-- 통제 픽스처(정수 원). current_highest_bid NULL, 상한 근처 큰 값, payment 행 포함.
INSERT INTO music   VALUES ('m1',10000,11000),('m2',20000,NULL),('m3',15000,15000);
INSERT INTO bid     VALUES ('b1',11000),('b2',12000),('b3',9999999999);
INSERT INTO payment VALUES ('p1',11000),('p2',12000);

-- 원본 스냅샷(복구 대조용, 백업과 독립)
CREATE TABLE snap_music   AS SELECT * FROM music;
CREATE TABLE snap_bid     AS SELECT * FROM bid;
CREATE TABLE snap_payment AS SELECT * FROM payment;

-- preflight 가 소수 행을 실제로 잡는지 증명: 소수 행 임시 삽입 → 탐지 1 → 제거
INSERT INTO bid VALUES ('bfrac', 11000.9);
SELECT 'PREFLIGHT_CATCHES_FRACTION' AS chk,
       (SELECT COUNT(*) FROM bid WHERE price<>FLOOR(price)) AS n;
DELETE FROM bid WHERE bid_uuid='bfrac';

-- STEP1 preflight — 소수/범위초과 0건 기대
SELECT 'PREFLIGHT_ABNORMAL' AS chk, (
   (SELECT COUNT(*) FROM music WHERE (starting_price IS NOT NULL AND starting_price<>FLOOR(starting_price)) OR (current_highest_bid IS NOT NULL AND current_highest_bid<>FLOOR(current_highest_bid)) OR starting_price<0 OR starting_price>10000000000)
 + (SELECT COUNT(*) FROM bid WHERE price<>FLOOR(price) OR price<0 OR price>10000000000)
 + (SELECT COUNT(*) FROM payment WHERE price<>FLOOR(price) OR price<0 OR price>10000000000)) AS n;

-- STEP2 backup
CREATE TABLE nb2_backup_music   AS SELECT music_uuid,starting_price,current_highest_bid FROM music;
CREATE TABLE nb2_backup_bid     AS SELECT bid_uuid,price FROM bid;
CREATE TABLE nb2_backup_payment AS SELECT payment_uuid,price FROM payment;

-- STEP3 migrate
ALTER TABLE music   MODIFY starting_price BIGINT;
ALTER TABLE music   MODIFY current_highest_bid BIGINT;
ALTER TABLE bid     MODIFY price BIGINT NOT NULL;
ALTER TABLE payment MODIFY price BIGINT NOT NULL;

-- STEP4 verify (전부 0 기대)
SELECT 'VERIFY_ROWCOUNT_DIFF' AS chk,
   (((SELECT COUNT(*) FROM music)<>(SELECT COUNT(*) FROM nb2_backup_music))
  + ((SELECT COUNT(*) FROM bid)<>(SELECT COUNT(*) FROM nb2_backup_bid))
  + ((SELECT COUNT(*) FROM payment)<>(SELECT COUNT(*) FROM nb2_backup_payment))) AS n;
SELECT 'VERIFY_PK_SYMDIFF' AS chk, (
   (SELECT COUNT(*) FROM nb2_backup_music b LEFT JOIN music m ON m.music_uuid=b.music_uuid WHERE m.music_uuid IS NULL)
 + (SELECT COUNT(*) FROM music m LEFT JOIN nb2_backup_music b ON m.music_uuid=b.music_uuid WHERE b.music_uuid IS NULL)
 + (SELECT COUNT(*) FROM nb2_backup_bid b LEFT JOIN bid m ON m.bid_uuid=b.bid_uuid WHERE m.bid_uuid IS NULL)
 + (SELECT COUNT(*) FROM bid m LEFT JOIN nb2_backup_bid b ON m.bid_uuid=b.bid_uuid WHERE b.bid_uuid IS NULL)
 + (SELECT COUNT(*) FROM nb2_backup_payment b LEFT JOIN payment m ON m.payment_uuid=b.payment_uuid WHERE m.payment_uuid IS NULL)
 + (SELECT COUNT(*) FROM payment m LEFT JOIN nb2_backup_payment b ON m.payment_uuid=b.payment_uuid WHERE b.payment_uuid IS NULL)) AS n;
SELECT 'VERIFY_VALUE_DIFF' AS chk, (
   (SELECT COUNT(*) FROM music m JOIN nb2_backup_music b ON m.music_uuid=b.music_uuid WHERE NOT(m.starting_price<=>CAST(b.starting_price AS SIGNED)) OR NOT(m.current_highest_bid<=>CAST(b.current_highest_bid AS SIGNED)))
 + (SELECT COUNT(*) FROM bid m JOIN nb2_backup_bid b ON m.bid_uuid=b.bid_uuid WHERE NOT(m.price<=>CAST(b.price AS SIGNED)))
 + (SELECT COUNT(*) FROM payment m JOIN nb2_backup_payment b ON m.payment_uuid=b.payment_uuid WHERE NOT(m.price<=>CAST(b.price AS SIGNED)))) AS n;
SELECT 'PAYMENT_AFTER_TYPE' AS chk, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='nb2_rehearsal' AND TABLE_NAME='payment' AND COLUMN_NAME='price';

-- STEP5 복구(롤백) 리허설 — 백업에서 원상 복구 후 원본 스냅샷과 동일함을 확인
ALTER TABLE music   MODIFY starting_price DOUBLE;
ALTER TABLE music   MODIFY current_highest_bid DOUBLE;
ALTER TABLE bid     MODIFY price DOUBLE;
ALTER TABLE payment MODIFY price DOUBLE;
UPDATE music m   JOIN nb2_backup_music b   ON m.music_uuid=b.music_uuid     SET m.starting_price=b.starting_price, m.current_highest_bid=b.current_highest_bid;
UPDATE bid m     JOIN nb2_backup_bid b     ON m.bid_uuid=b.bid_uuid         SET m.price=b.price;
UPDATE payment m JOIN nb2_backup_payment b ON m.payment_uuid=b.payment_uuid SET m.price=b.price;
SELECT 'RECOVERY_VALUE_DIFF_vs_SNAP' AS chk, (
   (SELECT COUNT(*) FROM music m JOIN snap_music s ON m.music_uuid=s.music_uuid WHERE NOT(m.starting_price<=>s.starting_price) OR NOT(m.current_highest_bid<=>s.current_highest_bid))
 + (SELECT COUNT(*) FROM bid m JOIN snap_bid s ON m.bid_uuid=s.bid_uuid WHERE NOT(m.price<=>s.price))
 + (SELECT COUNT(*) FROM payment m JOIN snap_payment s ON m.payment_uuid=s.payment_uuid WHERE NOT(m.price<=>s.price))) AS n;

DROP DATABASE nb2_rehearsal;
