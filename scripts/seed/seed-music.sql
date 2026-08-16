-- ============================================================================
-- Phase 1 성능 측정용 시드 데이터 스크립트
--
-- 무엇을 하나:
--   1) 측정 대상 테이블(payment, likes, bid, music)을 비운다 (user는 유지)
--   2) 시드 입찰자 10명을 만든다 (seed-bidder-1..10@test.local, 비밀번호는
--      기존 bidder@test.local 계정과 동일 — bcrypt 해시를 복사)
--   3) 곡 N건을 넣는다:
--        - 오디오 더미 3MB, 이미지 더미 300KB (실제 음원/커버 크기 흉내)
--        - 짝수 번째 곡 = 경매 마감 시각이 과거 (스케줄러의 종료 처리 대상)
--        - 홀수 번째 곡 = 마감 시각이 미래 (진행 중)
--        - 곡마다 입찰 0~5건 (i mod 6), 가격은 시작가 10,000원 + 1,000원씩 증가
--
-- 사용법 (레포 루트에서):
--   docker exec -i notenest-db mariadb -uroot -plocal-only notenest < scripts/seed/seed-music.sql
--   곡 수를 바꾸려면 맨 아래 CALL seed_music(100) 의 숫자를 100/500/1000으로 수정.
--
-- 주의:
--   - N=1000이면 오디오만 약 3GB가 들어가므로 수 분 걸릴 수 있다.
--   - 실행 전제: 스모크 시드 계정 composer@test.local, bidder@test.local 존재
--     (없으면 회원가입 API로 먼저 생성 — 비밀번호 test1234)
-- ============================================================================

DELIMITER //

DROP PROCEDURE IF EXISTS seed_music //

CREATE PROCEDURE seed_music(IN p_music_count INT)
BEGIN
    DECLARE v_i INT DEFAULT 1;
    DECLARE v_j INT DEFAULT 1;
    DECLARE v_bid_count INT;
    DECLARE v_music_uuid UUID;
    DECLARE v_bidder_uuid UUID;
    DECLARE v_composer_uuid UUID;
    DECLARE v_pw VARCHAR(255);
    DECLARE v_audio LONGBLOB;
    DECLARE v_image LONGBLOB;
    DECLARE v_end_time DATETIME(6);
    DECLARE v_max_price DOUBLE;

    -- 곡 소유자(작곡가)와 시드 입찰자 비밀번호 해시(bcrypt of 'test1234') 확보
    SELECT user_uuid INTO v_composer_uuid FROM user WHERE email = 'composer@test.local';
    SELECT password INTO v_pw FROM user WHERE email = 'bidder@test.local';
    IF v_composer_uuid IS NULL OR v_pw IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT =
            'composer@test.local / bidder@test.local 계정이 필요합니다 (회원가입 API로 먼저 생성)';
    END IF;

    -- 1) 초기화 (FK 순서: payment -> likes -> bid -> music)
    DELETE FROM payment;
    DELETE FROM likes;
    DELETE FROM bid;
    DELETE FROM music;

    -- 2) 시드 입찰자 10명 (이미 있으면 무시)
    SET v_j = 1;
    WHILE v_j <= 10 DO
        INSERT IGNORE INTO user (user_uuid, agreement, email, email_verified, name, nickname, password, phone_no, role)
        VALUES (UUID(), b'1', CONCAT('seed-bidder-', v_j, '@test.local'), b'1',
                CONCAT('시드입찰자', v_j), CONCAT('seed-bidder-', v_j), v_pw, '010-0000-0000', 'ROLE_ADMIN');
        SET v_j = v_j + 1;
    END WHILE;

    -- 3) 더미 바이너리 (오디오 3MB, 이미지 300KB)
    SET v_audio = REPEAT('a', 3 * 1024 * 1024);
    SET v_image = REPEAT('i', 300 * 1024);

    -- 4) 곡 + 입찰 삽입
    SET autocommit = 0;
    WHILE v_i <= p_music_count DO
        SET v_music_uuid = UUID();
        -- 짝수 곡 = 마감 과거(종료 대상), 홀수 곡 = 마감 미래(진행 중)
        IF v_i % 2 = 0 THEN
            SET v_end_time = NOW() - INTERVAL ((v_i MOD 24) + 1) HOUR;
        ELSE
            SET v_end_time = NOW() + INTERVAL 7 DAY;
        END IF;

        SET v_bid_count = v_i MOD 6;  -- 곡당 입찰 0~5건
        IF v_bid_count > 0 THEN
            SET v_max_price = 10000 + v_bid_count * 1000;
        ELSE
            SET v_max_price = NULL;
        END IF;

        INSERT INTO music (music_uuid, auction_end_time, auction_failure_email_sent, audio, created_at,
                           current_highest_bid, details, hashtag, hit_song_composer, image, like_count,
                           major_genre, music_period, popular_composer, show_all_bids, starting_price,
                           status, steady_work_composer, subtitle, title, user_uuid)
        VALUES (v_music_uuid, v_end_time, b'0', v_audio, NOW() - INTERVAL v_i MINUTE,
                v_max_price, CONCAT('시드 곡 ', v_i, ' 상세 설명'), '#seed', b'0', v_image, 0,
                'POP', 7, b'0', b'1', 10000,
                0, b'0', CONCAT('부제 ', v_i), CONCAT('seed song ', v_i), v_composer_uuid);

        -- 입찰 삽입 (가격 오름차순 — 마지막 입찰이 최고가)
        SET v_j = 1;
        WHILE v_j <= v_bid_count DO
            SELECT user_uuid INTO v_bidder_uuid FROM user
             WHERE email = CONCAT('seed-bidder-', ((v_i + v_j) MOD 10) + 1, '@test.local');
            INSERT INTO bid (bid_uuid, bidder_email_sent, composer_email_sent, created_at, imp_uid,
                             price, status, music_uuid, user_uuid)
            VALUES (UUID(), b'0', b'0', NOW() - INTERVAL (v_bid_count - v_j) MINUTE, NULL,
                    10000 + v_j * 1000, NULL, v_music_uuid, v_bidder_uuid);
            SET v_j = v_j + 1;
        END WHILE;

        -- 50곡마다 커밋 (대량 삽입 시 트랜잭션 비대화 방지)
        IF v_i % 50 = 0 THEN
            COMMIT;
        END IF;
        SET v_i = v_i + 1;
    END WHILE;
    COMMIT;
    SET autocommit = 1;

    -- 결과 요약 출력
    SELECT COUNT(*) AS seeded_music,
           SUM(CASE WHEN auction_end_time < NOW() THEN 1 ELSE 0 END) AS ended_music
      FROM music;
    SELECT COUNT(*) AS seeded_bids FROM bid;
END //

DELIMITER ;

-- ★ 곡 수를 여기서 조정: 100 / 500 / 1000
CALL seed_music(100);
