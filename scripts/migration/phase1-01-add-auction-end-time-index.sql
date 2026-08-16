-- [Phase 1 세션 2] 스케줄러 종료 대상 선별용 인덱스
-- Music 엔티티의 @Table(indexes=...)로 ddl-auto가 생성하지만,
-- ddl-auto를 validate로 전환(세션 3 예정)한 뒤에는 이 스크립트가 스키마 관리의 기준이 된다.
--
-- InnoDB 보조 인덱스는 PK(music_uuid)를 포함하므로,
-- "SELECT music_uuid FROM music WHERE auction_end_time < ?" 쿼리는
-- 이 인덱스만으로 커버링(테이블 본체의 Lob 페이지를 전혀 읽지 않음)된다.
CREATE INDEX IF NOT EXISTS idx_music_auction_end_time ON music (auction_end_time);
