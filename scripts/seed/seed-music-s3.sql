-- ============================================================================
-- 성능 측정용 시드 — NB1 이후 스키마(객체 저장소, LOB 컬럼 없음)
--
-- seed-music.sql 과 같은 곡·입찰 구조를 만든다. 차이는 미디어뿐이다.
--   - 곡 N건: 짝수 번째 = 마감 과거(종료 대상), 홀수 번째 = 마감 미래(진행 중)
--   - 곡당 입찰 0~5건(i mod 6), 가격은 시작가 10,000원 + 1,000원씩 증가, 입찰자는 시드 입찰자 10명
--   - 미디어: LOB 대신 모든 곡이 공용 더미 객체 두 개를 가리킨다
--       커버      seed/cover      (300KB, application/octet-stream)
--       전체 데모  seed/full-demo  (3MB,   application/octet-stream)
--     미리듣기는 없다(NB1 이전 시드와 같은 "기존 곡" 모양).
--
-- 측정 영향: 목록 API 와 경매 배치는 객체 내용을 읽지 않고 키로 URL 만 만든다.
--   그래서 공용 객체를 가리켜도 DB 쪽 측정 조건(행 수·입찰 분포·조회 SQL)은 곡마다 따로 있을 때와 같다.
--
-- 사전 조건:
--   1) nb1-02(객체 키 컬럼)·nb1-03(LOB 삭제) 적용된 스키마
--   2) 버킷에 seed/cover, seed/full-demo 객체가 있어야 URL 이 실제로 열린다(측정 자체는 객체 없이도 돌아간다).
--      더미 파일 만들기: python -c "open('cover','wb').write(b'i'*300*1024); open('full-demo','wb').write(b'a'*3*1024*1024)"
--      S3 콘솔에서 notenest-media 버킷에 각각 seed/cover, seed/full-demo 로 업로드
--   3) composer@test.local, bidder@test.local 계정 존재 (비밀번호 test1234)
--
-- 주의: 시드 곡을 곡 삭제 API 로 지우면 공용 객체가 함께 삭제된다. 측정용으로만 쓰고 API 로 삭제하지 않는다.
--
-- 사용법 (레포 루트에서):
--   docker exec -i notenest-db mariadb -uroot -plocal-only notenest < scripts/seed/seed-music-s3.sql
--   곡 수를 바꾸려면 프로시저를 직접 호출: CALL seed_music_s3(500);
-- ============================================================================

DELIMITER //

DROP PROCEDURE IF EXISTS seed_music_s3 //

CREATE PROCEDURE seed_music_s3(IN p_music_count INT)
BEGIN
    DECLARE v_i INT DEFAULT 1;
    DECLARE v_j INT DEFAULT 1;
    DECLARE v_bid_count INT;
    DECLARE v_music_uuid UUID;
    DECLARE v_bidder_uuid UUID;
    DECLARE v_composer_uuid UUID;
    DECLARE v_pw VARCHAR(255);
    DECLARE v_end_time DATETIME(6);
    DECLARE v_max_price BIGINT;

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

    -- 2) 시드 입찰자 10명 (이미 있으면 무시). 가입 기본 역할과 같은 ROLE_USER.
    SET v_j = 1;
    WHILE v_j <= 10 DO
        INSERT IGNORE INTO user (user_uuid, agreement, email, email_verified, name, nickname, password, phone_no, role)
        VALUES (UUID(), b'1', CONCAT('seed-bidder-', v_j, '@test.local'), b'1',
                CONCAT('시드입찰자', v_j), CONCAT('seed-bidder-', v_j), v_pw, '010-0000-0000', 'ROLE_USER');
        SET v_j = v_j + 1;
    END WHILE;

    -- 3) 곡 + 입찰 삽입
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

        INSERT INTO music (music_uuid, auction_end_time, auction_failure_email_sent, created_at,
                           current_highest_bid, details, hashtag, hit_song_composer, like_count,
                           major_genre, music_period, popular_composer, show_all_bids, starting_price,
                           status, steady_work_composer, subtitle, title, user_uuid,
                           cover_object_key, cover_content_type, cover_size,
                           full_demo_object_key, full_demo_content_type, full_demo_size)
        VALUES (v_music_uuid, v_end_time, b'0', NOW() - INTERVAL v_i MINUTE,
                v_max_price, CONCAT('시드 곡 ', v_i, ' 상세 설명'), '#seed', b'0', 0,
                'POP', 7, b'0', b'1', 10000,
                0, b'0', CONCAT('부제 ', v_i), CONCAT('seed song ', v_i), v_composer_uuid,
                'seed/cover', 'application/octet-stream', 300 * 1024,
                'seed/full-demo', 'application/octet-stream', 3 * 1024 * 1024);

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
CALL seed_music_s3(100);
