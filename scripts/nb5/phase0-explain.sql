-- NB5 Phase 0 — 현재 LIKE 검색 SQL(앱 로그 발췌와 동일 형태)의 실행 계획
-- 대상: notenest_nb5 (격리 스키마). 사용법(레포 루트):
--   docker exec -i notenest-db mariadb -uroot -plocal-only --default-character-set=utf8mb4 -t < scripts/nb5/phase0-explain.sql
USE notenest_nb5;
SET NAMES utf8mb4;
SELECT VERSION() AS version, (SELECT COUNT(*) FROM music) AS music_rows, (SELECT COUNT(*) FROM music WHERE status=0) AS ongoing_rows;
SHOW INDEX FROM music;

-- 본문 쿼리 (searchTerm=봄비, 1페이지 10건)
EXPLAIN
select m1_0.music_uuid, m1_0.title, m1_0.starting_price, u1_0.nickname, m1_0.current_highest_bid,
       m1_0.auction_end_time, m1_0.like_count, m1_0.cover_object_key
from music m1_0 left join user u1_0 on u1_0.user_uuid=m1_0.user_uuid
where m1_0.status=0 and (m1_0.title like '%봄비%' escape '!' or m1_0.subtitle like '%봄비%' escape '!'
   or m1_0.major_genre like '%봄비%' escape '!' or m1_0.hashtag like '%봄비%' escape '!' or u1_0.nickname like '%봄비%' escape '!')
order by m1_0.created_at desc limit 0, 10;

-- 실제 실행 통계: 선택도가 다른 세 검색어(봄비 8건, love 488건, sad 1333건)
ANALYZE FORMAT=JSON
select m1_0.music_uuid, m1_0.title, m1_0.starting_price, u1_0.nickname, m1_0.current_highest_bid,
       m1_0.auction_end_time, m1_0.like_count, m1_0.cover_object_key
from music m1_0 left join user u1_0 on u1_0.user_uuid=m1_0.user_uuid
where m1_0.status=0 and (m1_0.title like '%봄비%' escape '!' or m1_0.subtitle like '%봄비%' escape '!'
   or m1_0.major_genre like '%봄비%' escape '!' or m1_0.hashtag like '%봄비%' escape '!' or u1_0.nickname like '%봄비%' escape '!')
order by m1_0.created_at desc limit 0, 10;

ANALYZE FORMAT=JSON
select m1_0.music_uuid, m1_0.title, m1_0.starting_price, u1_0.nickname, m1_0.current_highest_bid,
       m1_0.auction_end_time, m1_0.like_count, m1_0.cover_object_key
from music m1_0 left join user u1_0 on u1_0.user_uuid=m1_0.user_uuid
where m1_0.status=0 and (m1_0.title like '%love%' escape '!' or m1_0.subtitle like '%love%' escape '!'
   or m1_0.major_genre like '%love%' escape '!' or m1_0.hashtag like '%love%' escape '!' or u1_0.nickname like '%love%' escape '!')
order by m1_0.created_at desc limit 0, 10;

ANALYZE FORMAT=JSON
select m1_0.music_uuid, m1_0.title, m1_0.starting_price, u1_0.nickname, m1_0.current_highest_bid,
       m1_0.auction_end_time, m1_0.like_count, m1_0.cover_object_key
from music m1_0 left join user u1_0 on u1_0.user_uuid=m1_0.user_uuid
where m1_0.status=0 and (m1_0.title like '%sad%' escape '!' or m1_0.subtitle like '%sad%' escape '!'
   or m1_0.major_genre like '%sad%' escape '!' or m1_0.hashtag like '%sad%' escape '!' or u1_0.nickname like '%sad%' escape '!')
order by m1_0.created_at desc limit 0, 10;

-- count 쿼리 (searchTerm=봄비)
EXPLAIN
select count(m1_0.music_uuid)
from music m1_0 left join user u1_0 on u1_0.user_uuid=m1_0.user_uuid
where m1_0.status=0 and (m1_0.title like '%봄비%' escape '!' or m1_0.subtitle like '%봄비%' escape '!'
   or m1_0.major_genre like '%봄비%' escape '!' or m1_0.hashtag like '%봄비%' escape '!' or u1_0.nickname like '%봄비%' escape '!');

ANALYZE FORMAT=JSON
select count(m1_0.music_uuid)
from music m1_0 left join user u1_0 on u1_0.user_uuid=m1_0.user_uuid
where m1_0.status=0 and (m1_0.title like '%봄비%' escape '!' or m1_0.subtitle like '%봄비%' escape '!'
   or m1_0.major_genre like '%봄비%' escape '!' or m1_0.hashtag like '%봄비%' escape '!' or u1_0.nickname like '%봄비%' escape '!');

-- 비교: 검색어 없는 기존 목록 1페이지(최신순)
EXPLAIN
select m1_0.music_uuid, m1_0.title from music m1_0 left join user u1_0 on u1_0.user_uuid=m1_0.user_uuid
where m1_0.status=0 order by m1_0.created_at desc limit 0, 10;
