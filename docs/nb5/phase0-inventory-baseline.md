# NB5 Phase 0 — 검색 인벤토리와 현재 LIKE 기준선

측정일: 2026-09-26 / 코드: `608e7e0` (브랜치 `feat/nb5-product-search`) / JVM: Java 17.0.12 (toolchain) / DB: MariaDB 10.11.18 (`notenest-db` 컨테이너)

이 문서는 Phase 0 산출물이다. Elasticsearch 의존성·Docker 서비스는 추가하지 않았고, 운영 코드(`src/`)도 바꾸지 않았다.
평가 코퍼스·질의셋은 **도메인 인터뷰를 반영한 검증용 가설**이며, 실제 사용자 로그나 실데이터가 아니다.

## 0. 요약

- **문제는 재현된다.** 현재 검색은 관련도 없이 `%keyword%` OR 조건을 최신순으로 정렬한다. 그래서 정확한 제목이나 판매자를 입력해도 결과 순서가 등록 시각으로 정해진다.
  - 곡명 탐색: `봄비` 6위, `여름밤` 4위, `love` 4위(57곡) → 358위(10,000곡). `Glove`·`Clover`가 `Love`보다 위에 온다.
  - 공급자 탐색: `새벽공방`을 입력하면 판매자 본인 곡이 3~6위이고, 닉네임이 겹치는 `새벽공방스튜디오`의 곡과 제목에 해당 단어가 들어간 다른 판매자 곡이 그 위에 온다.
  - 한글 분석 부재: 띄어쓰기 변형(`여름 밤`, `새벽 공방`, `bluehour`)은 의도한 곡을 찾지 못한다. 여러 단어를 넣으면(`잔잔한 피아노`) 단어가 붙어 있는 1곡만 나온다. 한글 장르명(`발라드`, `힙합`)은 장르 코드(`balad`, `hiphop`)와 맞지 않는다.
  - 상세 설명(`details`)은 검색 대상이 아니다. 판매자가 설명에 적은 BPM·키 정보(`BPM 90 / Key: Am`)는 검색으로 찾을 수 없다.
  - 대소문자 차이는 문제가 아니다. collation `utf8mb4_unicode_ci`이라 `Blue Hour`와 `BLUE HOUR`는 결과가 같다.
- **BPM·키 필터는 현재 표현할 수 없다.** 알 수 없는 파라미터(`bpmMin` 등)는 오류 없이 무시되고, 필터가 적용되지 않은 결과가 200으로 반환된다(Q13: 2,004건).
- **실행 계획:** `music` 풀스캔 → 진행 중 행 전체(8,036건) filesort → 정렬된 순서대로 `user` 조인·LIKE 평가. 매칭이 적은 검색어일수록 LIMIT에서 일찍 멈추지 못해 느리다(봄비 약 20~27ms, love·sad 약 3~6ms, SQL 단독).
- **API 성능(10,000곡, 10 VU × 30s):** 검색 p50 51.0ms / p90 67.6ms, 비검색 대조군 p50 37.3ms / p90 48.7ms (웜 1회차). 성능 수치는 기준선일 뿐이고, 현재 규모에서 LIKE가 성능 병목이라는 근거로는 쓰지 않는다. 문제의 본질은 **품질(관련도·분석)** 이다.
- **다음 단계 판단:** Phase 1(qrels 확정·평가 하네스·BPM/키 스키마)로 갈 근거가 충분하다. 단, 아래 §7의 결정 사항을 먼저 확정해야 한다.

## 1. 실행 조건과 격리

| 항목 | 값 |
|---|---|
| 측정 DB | 같은 컨테이너의 **새 스키마 `notenest_nb5`** (기존 `notenest`·`notenest_contract`는 읽기만 함, `notenest.music` 100건 유지 확인) |
| 스키마 생성 | 앱 `ddl-auto=update` 가 `notenest_nb5` 에 테이블 생성 (인덱스: PK, `idx_music_auction_end_time`, FK `user_uuid`) |
| 앱 | `./gradlew bootRun`, `SERVER_PORT=8096`, `SPRING_DATASOURCE_URL=.../notenest_nb5`, local 프로파일(SQL 로그 켜짐) |
| S3 | 더미 자격증명·버킷으로 **URL 서명만** 한다(로컬 계산). 원본 GET 은 없다. 원시 결과에는 URL 을 저장하지 않았다 |
| 스케줄러 | 실행 중이었지만 대상 0건(진행 곡 마감일 2030-12-31, 종료 곡 status=1) — 로그 `targets=0` 72회 |
| 코퍼스 | 평가 57곡(`corpus-v0.jsonl`) + 결정적 filler(시드 20260926) → 10,000곡(진행 중 8,036) / 판매자 210명 |
| 부하 | k6 v2.2.0, 10 VU × 30s, 반복 사이 0.1s, 비로그인. 콜드 1회 버림 → 웜 2회 기록 |

등록 시각(`created_at`)은 곡 id 의 SHA-256 순서로 배정했다. 표적 곡을 일부러 앞이나 뒤에 두지 않으려는 것이다. 그래서 LIKE 결과에서 표적 곡의 순위는 관련도와 무관한 등록 순서로 정해진다.

## 2. 현재 검색 계약 인벤토리

### 2.1 API — `GET /api/music/filter` ([MusicController.java:128](../../src/main/java/com/notenest/controller/MusicController.java))

| 파라미터 | 기본값 | 동작 |
|---|---|---|
| `searchTerm` | 없음 | trim 후 `%kw%` 를 제목·부제·장르·해시태그·판매자 닉네임에 OR ([MusicRepositoryCustomImpl.java:84](../../src/main/java/com/notenest/repository/MusicRepositoryCustomImpl.java)). **상세 설명(`details`)은 대상이 아니다** |
| `majorGenre` | 없음 | 정확 일치 `eq` |
| `hashtag` | 없음 | 콤마로 나눠 각 태그를 **`contains`(부분 일치) AND** ([:98](../../src/main/java/com/notenest/repository/MusicRepositoryCustomImpl.java)) |
| `minPrice`·`maxPrice` | 없음 | `coalesce(currentHighestBid, startingPrice)` 에 between/goe/loe ([:104](../../src/main/java/com/notenest/repository/MusicRepositoryCustomImpl.java)) |
| `sortBy` | `latest` | `latest`=`createdAt desc` / `price`=`currentHighestBid desc nulls last, startingPrice desc` / `like`=`likeCount desc`. 그 밖의 값은 latest |
| `page`·`size` | 0·10 | `PageRequest.of`. 상한 검증 없음 |
| 항상 | — | `status = 0`(진행 중)만 |

- 검색어 유무와 관계없이 **단일 QueryDSL 경로**를 탄다. 요청당 SQL 2개(본문 + count)이고, 로그인 시 좋아요 IN 조회 1개가 추가된다. SQL 원문: [raw/phase0-like-hibernate-sql.txt](raw/phase0-like-hibernate-sql.txt)
- QueryDSL `contains` 는 입력의 `%`·`_` 를 `!` 로 이스케이프한다(와일드카드 주입 없음).
- 예외는 모두 500 + `null` 본문으로 응답한다. 알 수 없는 파라미터는 무시된다.

### 2.2 응답 DTO — `Page<MusicSummaryDTO>`

`musicUuid, title, startingPrice, userNickName, currentHighestBid, auctionEndTime, likeCount, coverUrl, likedByUser` + Spring `Page` 메타데이터(`sort` 포함). `coverObjectKey` 는 `@JsonIgnore`. `spring.jackson.default-property-inclusion=non_null` 이라 null 필드는 빠진다.
NB5 에서 유지할 계약: 필드명·Page 메타데이터·좋아요 IN 조회·커버 URL 발급(`MediaUrlIssuer`)·raw 키 비노출. 이 계약은 이미 [MusicFilterContractTest](../../src/test/java/com/notenest/contract/MusicFilterContractTest.java) 가 고정하고 있다(검색 결과 집합, 필드별 검색, 정렬, 페이지, 커버 URL, audio 부재).

### 2.3 정렬 tie-break

- 세 정렬 모두 **`musicId` 같은 고유 tie-break 가 없다.** `price` 는 `startingPrice` 까지만, `like`·`latest` 는 단일 키다.
- 57곡에서 `like`·`price`·`latest` 를 size 5로 12페이지 넘겨 봤을 때 중복·누락은 없었다(55/55). 하지만 같은 값이 많은 `likeCount` 에서 페이지 간 순서가 보장되는 계약은 아니다. Phase 3에서 계획대로 `createdAt, musicId` tie-break 를 둔다.

### 2.4 입력 데이터의 실제 모양 (원 프론트엔드 `bidder-front` 읽기 전용 확인)

- `majorGenre`: `pop`·`balad`(오타가 값으로 굳음)·`hiphop`·`trot` 4개 코드. 백엔드는 값을 검증하지 않는다.
- `hashtag`: 고정 12개 영문 태그(`#comic #bright #fresh #groovy #hope #sad #pop #dance #electronic #jazz #indie #rock`)를 **공백으로 이은 문자열** 한 컬럼.
- 한글 검색어 `발라드`·`힙합` 은 장르·태그와 맞지 않는다. 동의어·표시명 매핑이 필요하다(§7).

### 2.5 ACTION_PLAN 과의 차이

| ACTION_PLAN 기재 | 코드 사실 | 영향 |
|---|---|---|
| 기존 텍스트 검색 필드에 "상세 설명" 포함 | `details` 는 검색하지 않는다 | 색인 필드로 넣는 것은 **검색 범위 확장**(계약 변경)이다. 기존 계약 보존 테스트와 구분해야 한다 |
| 판매자 닉네임 변경 fan-out 은 P0 제외 | **닉네임 변경 API 자체가 없다**(UserService 에 닉네임 수정 경로 없음) | P0 에서 fan-out 한계는 "현재 발생 경로 없음, 대조 작업으로만 대비"로 기록 가능 |
| 해시태그 필터 | `contains` 부분 일치 | 현재 고정 태그 12개에서는 부분 일치 충돌이 없다. 자유 입력이 생기면 오탐 가능(`#pop` ⊂ 가상의 `#kpop`) |

## 3. 검색 문서에 영향을 주는 변경 경로

| 변경 | 진입점 | 저장 지점 | 트랜잭션 경계 | 바뀌는 검색 필드 |
|---|---|---|---|---|
| 곡 등록 | `POST /api/music/create` | [MusicServiceImpl.java:162](../../src/main/java/com/notenest/service/MusicServiceImpl.java) `musicRepository.save` | 서비스 메서드는 트랜잭션 밖. `save` 자체 트랜잭션이 커밋된 뒤 반환 | 전 필드 신규 |
| 곡 수정 | `PUT /api/music/{uuid}` | [:249](../../src/main/java/com/notenest/service/MusicServiceImpl.java) `musicRepository.save` | 동일(트랜잭션 밖, save 커밋 후 반환). 실패 시 새 객체 보상 삭제 | 제목·부제·장르·설명·태그·커버 키 |
| 곡 삭제 | `DELETE /api/music/{uuid}` | [:184](../../src/main/java/com/notenest/service/MusicServiceImpl.java) `musicRepository.delete` | 트랜잭션 밖. 입찰이 있으면 409 로 거부 | 문서 삭제 |
| 입찰(현재가) | `POST /api/bid/create` | [BidServiceImpl.java:96](../../src/main/java/com/notenest/service/BidServiceImpl.java) `music.setCurrentHighestBid` 후 `bidRepository.save(bid)` | **`@Transactional` 없음.** `music` 변경은 OSIV 영속성 컨텍스트에 남아 있다가 `bid` save 트랜잭션 커밋 때 함께 flush 된다(명시 save 아님) | `currentHighestBid` |
| 입찰 삭제 | `DELETE /api/bid/{uuid}` | [BidServiceImpl.java:103](../../src/main/java/com/notenest/service/BidServiceImpl.java) | 트랜잭션 밖 | **현재가를 다시 계산하지 않는다** — DB 의 `currentHighestBid` 가 이미 삭제된 입찰가로 남는다(기존 결함, NB5 범위 밖). 색인은 DB 를 그대로 따라가면 된다 |
| 좋아요 토글 | `POST /api/music/{uuid}/like` | [LikeMusicService.java:78·86](../../src/main/java/com/notenest/service/LikeMusicService.java) | `@Transactional`(메서드) → AFTER_COMMIT 가능 | `likeCount` |
| 경매 마감 | `@Scheduled checkAuctionEnd` 10초 | [BidServiceImpl.java:202](../../src/main/java/com/notenest/service/BidServiceImpl.java) `setStatus(1)` | 스케줄러 메서드 하나가 **사이클 전체의 단일 트랜잭션**(자기 호출로 곡별 REQUIRES_NEW 무효, 기존 N5 주석) | `status` 0→1 (검색 노출 제외) |
| 결제 후속·경매 무산 | `checkPendingPayments`, `PaymentController` | `failAuction` 은 `auctionFailureEmailSent` 만 변경 | — | 검색 필드 변화 없음(`status` 는 이미 1) |
| 판매자 닉네임 | — | 변경 API 없음 | — | (fan-out 대상 없음) |

정리: 트랜잭션 경계가 경로마다 다르다(트랜잭션 없음·메서드 트랜잭션·사이클 단일 트랜잭션·OSIV 암묵 flush). ACTION_PLAN 의 "활성 트랜잭션이면 AFTER_COMMIT, 없으면 저장 반환 후" 계약이 필요하다는 판단은 코드로 확인된다. 특히 입찰 경로는 `music` 을 명시적으로 저장하지 않으므로, 이벤트 발행 위치를 `bidRepository.save` 반환 뒤로 둬야 한다.

## 4. 고정 코퍼스와 질의셋 초안

- 평가 코퍼스: [eval/corpus-v0.jsonl](eval/corpus-v0.jsonl) — 57곡(진행 중 55, 종료 2), 판매자 10명. 표적 곡과 혼동 후보를 묶음으로 설계했다.
  - 같은 제목의 종료 곡(A08), 제목을 포함하는 긴 제목, 부제·닉네임에만 검색어가 있는 곡, 부분 문자열 충돌(`Love`/`Glove`/`Clover`), 띄어쓰기 변형, 설명에만 특성이 있는 곡, 닉네임 접두 충돌(`새벽공방`/`새벽공방스튜디오`, `MINT`/`mintyard`), 제목과 판매자명이 같은 모호 사례(`윤슬`)
  - `bpm`·`key` 는 **아직 DB 에 없는 정답 속성**이다. 적재하지 않고 Phase 1 스키마 확정 후 사용한다. 일부 곡은 판매자가 설명에 BPM·키를 자유 표기한 것으로 설계했다(`BPM 90 / Key: Am`, `140bpm, F# minor`, `95 BPM, a min`, `라단조(Dm)`).
- filler 생성기: [scripts/nb5/nb5_corpus.py](../../scripts/nb5/nb5_corpus.py) — 원 프론트엔드의 장르 4종·태그 12종과 한·영 단어 풀로 결정적 생성. 출력 SQL 은 `USE notenest_nb5` 로 고정되어 있다.
- 질의셋: [eval/queries-v0.jsonl](eval/queries-v0.jsonl) — 25개

| 유형 | 수 | 예 |
|---|---|---|
| T1 곡명 탐색 | 9 | `봄비`, `여름밤`, `여름 밤`, `Blue Hour`, `BLUE HOUR`, `bluehour`, `너의 이름을 부르면`, `너의 이름`, `love` |
| T2 조건 탐색 | 6 | `비트`+hiphop+≤20만, `힙합`+≤20만, `BPM 90`, `hiphop`+bpm 85~95+Am(계획 파라미터), `120`, `Am`+hiphop |
| T3 분위기·특성 | 6 | `잔잔한 피아노`, `신나는 댄스`, `이별`, `sad`, `피아노 발라드`, `발라드` |
| T4 공급자 | 4 | `새벽공방`, `mint`, `윤슬`(모호), `새벽 공방` |

`primary` 필드는 **정의상 자명한 기대**만 적었다. 곡명 탐색의 정확 제목, 조건 탐색의 조건 충족 집합, 공급자 탐색의 판매자 본인 곡이다. 0~3 등급 qrels 가 아니며, T3·모호 질의(Q32)는 비워 두었다. 등급 qrels 는 Phase 1 에서 이 결과를 보기 전 기준으로 확정한다(아래 결과는 LIKE 결과이지 정답 근거가 아니다).

## 5. 현재 LIKE 검색 기준선

### 5.1 결과 순서 (전체: [raw/phase0-like-results-eval-only.md](raw/phase0-like-results-eval-only.md), [n10000](raw/phase0-like-results-n10000.md), [primary 전체 순위](raw/phase0-like-primary-ranks-n10000.txt))

| 질의 | 57곡: 매칭 / primary 순위 | 10,000곡: 매칭 / primary 순위 | 관찰 |
|---|---|---|---|
| Q01 `봄비` | 8 / A01 **6위** | 8 / 6위 | 닉네임 `봄비소리` 곡 4개가 위. 종료 곡 A08 은 제외됨(정상) |
| Q02 `여름밤` | 4 / B01 **4위** | 4 / 4위 | `한여름밤`·부제 매칭이 위 |
| Q03 `여름 밤` | 1 / B01 없음 | 1 / 없음 | 띄어쓰기 변형 실패 |
| Q04·Q05 `Blue Hour`/`BLUE HOUR` | 3 / C01 2위 | 3 / 2위 | 대소문자는 동일 결과(문제 아님), 정확 제목이 변형 제목 아래 |
| Q06 `bluehour` | 0 | 0 | 공백 누락 실패 |
| Q07·Q08 `너의 이름…` | D01 1위 | 1위 | 매칭 곡이 적어 우연히 정상 |
| Q09 `love` | 6 / E01 **4위** | 488 / **358위** | `Clover`·`Glove` 가 `Love` 위. 데이터가 늘면 정확 제목이 사실상 사라짐 |
| Q10 `비트`+hiphop+≤20만 | 2 / 모두 포함 | 46 / 모두 포함 | 조건 충족은 정상(T2 의 필터 결합은 LIKE 로도 동작) |
| Q11 `힙합`+≤20만 | 1 / 7곡 중 1곡 | 1 / 1곡 | 한글 장르명↔`hiphop` 불일치 |
| Q12 `BPM 90` | 0 | 0 | 설명의 BPM 표기 검색 불가 |
| Q13 계획 필터(bpm·key) | 11 (필터 무시) | **2,004** (필터 무시) | 알 수 없는 파라미터가 조용히 무시됨 |
| Q14 `120` | 1 (`120%의 에너지`) | 40 (닉네임 `filler_seller_120` 등) | 숫자 검색은 BPM 의도와 무관한 매칭 |
| Q15 `Am`+hiphop | 0 | 151 (`Dream` 등 부분 문자열) | 키 표기 검색 불가 + 오탐 |
| Q20 `잔잔한 피아노` | 1 (G01) | 1 | 어순이 다른 G02, 설명에만 있는 G03·F02 누락 |
| Q21 `신나는 댄스` | 1 (H03) | 1 | H01(`신나는 여름 댄스곡`)·H02(설명) 누락 |
| Q22 `이별` | 2 | 2 | 설명에만 있는 F03 누락 |
| Q23 `sad` | 17 | 1,333 | 태그 매칭 전부 동급, 순서는 등록순 |
| Q25 `발라드` | 2 | 1,014 | 장르 `balad` 곡이 아니라 부제에 '발라드'가 있는 곡만 매칭(10,000곡의 1,014건은 filler 부제 '감성 발라드') |
| Q30 `새벽공방` | 7 / 본인 곡 **3~6위** | 7 / 3~6위 | `새벽공방스튜디오`·제목 포함 곡이 위. 종료 곡 S05 제외(정상) |
| Q31 `mint` | 10 / MINT 곡 1·2·4~7위 | 10 / 같음 | `mintyard` 곡이 사이에 섞임 |
| Q33 `새벽 공방` | 1 (S07) / 본인 곡 없음 | 1 / 없음 | 띄어쓰기 변형 실패 |

진행 상태 계약: 모든 질의에서 종료 곡(status=1)이 결과에 나오지 않았다(`endedInTop` 전부 비어 있음).

### 5.2 실행 계획 ([raw/phase0-like-explain-n10000.txt](raw/phase0-like-explain-n10000.txt), 스크립트 [scripts/nb5/phase0-explain.sql](../../scripts/nb5/phase0-explain.sql))

| 쿼리 | music | user | Extra |
|---|---|---|---|
| 본문(검색) | `ALL` rows 9,960 | `eq_ref` PRIMARY | Using where; **Using filesort** |
| count(검색) | `ALL` rows 9,960 | `eq_ref` PRIMARY | Using where |
| 본문(비검색 최신순) | `ALL` | `eq_ref` | Using where; Using filesort |

- `status` 와 `created_at` 에 인덱스가 없다. 진행 중 8,036행을 모두 filesort 한 뒤 정렬 순서대로 조인·LIKE 를 평가한다. 닉네임 조건이 `user` 테이블에 있어 LIKE OR 전체가 조인 후 조건(`trigcond`)이 된다.
- `ANALYZE` 실행 시간(5회, ms, 파일 본문 1회 + 말미 2회 + 콘솔 확인 2회): 본문 `봄비` 19.9~26.8, `love` 3.1~6.4, `sad` 3.5~5.6, count `봄비` 9.2~16.1.
  매칭이 드문 검색어는 10건을 채우지 못해 끝까지 훑는다. count 는 항상 전체를 훑는다.
- 앞에 `%` 가 붙은 LIKE 는 B-tree 인덱스를 쓸 수 없다. 인덱스를 추가해도 텍스트 조건 자체의 풀스캔은 남는다(정렬 비용만 줄일 수 있다).

### 5.3 API 응답 성능 (10,000곡, 원시: `raw/phase0-k6-n10000-*.txt`)

| 모드 | 회차 | p50 | p90 | p95 | req/s | 오류 |
|---|---|---|---|---|---|---|
| 검색(25질의 균등 순환) | 웜1 | 50.97ms | 67.55ms | 72.56ms | 66.5 | 0% (0/2005) |
| 검색 | 웜2 | 53.98ms | 72.54ms | 80.11ms | 64.5 | 0% (0/1946) |
| 비검색 대조군 | 웜1 | 37.31ms | 48.67ms | 52.65ms | 71.5 | 0% (0/2152) |
| 비검색 대조군 | 웜2 | 33.56ms | 44.99ms | 49.37ms | 73.4 | 0% (0/2206) |

유형별 p90(검색 웜1): T1 69.2ms / T2 52.4ms / T3 66.2ms / T4 72.8ms.

해석상 주의:
- 처리량은 스크립트 상한(`sleep(0.1)`, 10 VU)에 묶여 있어 비교하지 않는다.
- local 프로파일의 SQL 콘솔 로그가 켜진 상태다. 이후 비교 측정도 같은 조건으로 해야 한다.
- 비검색 대조군도 약 35ms 다. 요청당 SQL 2개, 커버 URL 10개 서명, 로그 출력이 포함된 값이다. 검색의 추가 비용은 p50 기준 약 +15ms 수준이다.
- 이 규모에서 LIKE 는 **성능 문제가 아니라 품질 문제**다. Elasticsearch 가 더 빨라야 한다는 목표는 세우지 않는다(ACTION_PLAN 원칙과 동일).

## 6. BPM·musicalKey 정규화 후보 (결정 필요)

### 6.1 `bpm`

| 항목 | 후보안 | 근거·대안 |
|---|---|---|
| 타입 | `Integer`, nullable(기존 곡은 null) | 소수 BPM(예: 128.5)은 거부한다. NB2 금액 계약처럼 `accept-float-as-int=false` 로 소수 JSON 을 조용히 자르지 않는다 |
| 범위 | **40 ≤ bpm ≤ 250** (양끝 포함) | 발라드 60대~드릴 140·DnB 170대를 포함한다. 대안: 30~300(느슨) |
| 입력 | 등록 시 선택 입력, 수정 가능 | 첫 입찰 후 수정 허용 여부는 결정 필요(§7). 전체 데모 교체 금지 규칙과 맞추면 금지 |
| 필터 | `bpmMin`·`bpmMax` 포함 범위, 한쪽만 가능, `min > max` 는 400 | null BPM 곡은 BPM 필터가 있으면 제외 |
| 한계 | 하프/더블타임(70 ↔ 140) 동치는 다루지 않는다 | 한계로 기록 |

### 6.2 `musicalKey`

| 항목 | 후보안 |
|---|---|
| 정규 값 | **24개 = 12 음높이 × {major, minor}**. 이명동음은 한 값으로 합친다(C#=Db). 표기 대표값: 장조 `C Db D Eb E F F# G Ab A Bb B`, 단조 `Cm C#m Dm Ebm Em Fm F#m Gm G#m Am Bbm Bm` (관용 표기 기준) |
| 저장·API | enum 코드(예: `A_MINOR`, `F_SHARP_MINOR`, `D_FLAT_MAJOR`)와 표시명(`Am`, `F#m`, `Db`)을 분리. 응답·필터는 코드 |
| 입력 정규화 | 앞뒤 공백 제거, `♯/♭` → `#/b`, 대소문자 무시(단 단일 접미사 `m`/`M` 는 예외 규칙 필요), 수식어 `maj/major/장조` → major, `m/min/minor/단조` → minor, 수식어 없는 단일 음 → major. 예: `Am`, `A minor`, `a min`, `Amin` → `A_MINOR` / `F# minor`, `Gbm` → `F_SHARP_MINOR` |
| 한글 음이름 | `다라마바사가나` + `올림/내림` + `장조/단조`(예: `라단조` → D minor) — P0 포함 여부 결정 필요 |
| 거부 | 해석 불가 값은 400. 모호한 `AM`(대문자 M) 처리 규칙을 정해야 한다(후보: major 로 해석하지 않고 거부) |
| 필터 | 정확 일치 단일 값(`musicalKey=A_MINOR`). 관계조(C major ↔ A minor)·캄로트 휠 동치는 다루지 않는다 |

코퍼스의 자유 표기 예(`Key: Am`, `F# minor`, `a min`, `라단조(Dm)`, `Key C`)가 이 정규화 규칙의 테스트 입력 후보다.

## 7. 판단과 다음 단계

### 7.1 착수 게이트 대비

| 게이트 | Phase 0 결과 |
|---|---|
| `%keyword%` OR 검색의 관련도·한글 분석 한계를 고정 코퍼스로 재현 | **재현됨** (§5.1: 곡명 순위, 공급자 순위, 띄어쓰기·다단어·장르명·설명 누락, 부분 문자열 오탐) |
| qrels·기대 Top-K 를 구현 전에 확정 | 미완 — 질의셋 초안과 자명한 `primary` 만 있음 → Phase 1 |
| `bpm`·`musicalKey` 계약 확정 | 후보안만 제시 → 결정 필요 |
| 대안별 동일 질의 결과·EXPLAIN | LIKE 기준선만 완료. FULLTEXT·ES 는 Phase 2 |

### 7.2 다음 Phase 판단

**Phase 1(검색 계약과 평가 하네스)로 진행할 근거가 충분하다.** 현재 검색은 순위를 매기지 않으므로 곡명·공급자 탐색에서 관련도 요구를 충족하지 못한다. 이것은 데이터가 늘수록 악화된다(`love` 4위 → 358위). 성능은 현재 규모에서 판단 근거가 아니다.

다만 문제가 "검색엔진 부재" 하나로 모이지는 않는다. 일부는 **데이터 모델·계약 문제**다. 한글 장르명↔코드 매핑, `details` 검색 범위, BPM·키 구조화 필드가 여기에 해당한다. 이 부분은 어떤 대안을 택해도 Phase 1 에서 먼저 정해야 한다. MariaDB FULLTEXT 는 `innodb_ft_min_token_size=3`(현재 설정)이라 2글자 한글 단어(`이별`, `봄비`)가 색인되지 않을 가능성이 있다. 이 점은 Phase 2 스파이크에서 같은 질의로 확인한다.

### 7.3 Phase 1 착수 전 소민님 결정이 필요한 항목

1. **`details` 를 검색 대상에 넣을지** — 넣으면 기존 검색 결과 집합이 바뀐다(계약 확장). 기존 기능 동등성 테스트의 범위를 정해야 한다.
2. **한글 장르명 동의어**(`발라드`→`balad`, `힙합`→`hiphop`, `트로트`→`trot`, `케이팝/팝`→`pop`) — 검색 계약에 포함할지.
3. **BPM 범위(40~250 안)와 소수 거부**, 첫 입찰 후 BPM·키 수정 허용 여부.
4. **musicalKey**: 이명동음 통합, 한글 음이름 입력 P0 포함 여부, 모호 표기 `AM` 처리.
5. **Q32 `윤슬` 같은 제목·판매자 동명 질의의 등급 규칙** — 제목 정확 일치를 판매자 일치보다 우선할지.
6. **알 수 없는 파라미터 무시(Q13)를 그대로 둘지** — BPM·키 필터를 추가하면 구버전 클라이언트와의 호환 문제는 없다. 다만 현재처럼 오타 파라미터가 조용히 무시되는 동작은 남는다(범위 밖으로 기록만 하는 안을 권장).

## 8. 재현 방법 (레포 루트)

```bash
docker exec notenest-db mariadb -uroot -plocal-only -e "CREATE DATABASE IF NOT EXISTS notenest_nb5 CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
SERVER_PORT=8096 SPRING_DATASOURCE_URL="jdbc:mariadb://localhost:3311/notenest_nb5?serverTimezone=Asia/Seoul&characterEncoding=UTF-8" \
  AWS_ACCESS_KEY_ID=nb5-dummy AWS_SECRET_ACCESS_KEY=nb5-dummy NOTENEST_S3_BUCKET=nb5-dummy-bucket ./gradlew bootRun   # 테이블 생성
python scripts/nb5/nb5_corpus.py --total 57 > build/nb5/eval-only.sql
docker exec -i notenest-db mariadb -uroot -plocal-only --default-character-set=utf8mb4 < build/nb5/eval-only.sql
python scripts/nb5/nb5_capture.py --label eval-only --total 57
python scripts/nb5/nb5_corpus.py --total 10000 > build/nb5/n10000.sql
docker exec -i notenest-db mariadb -uroot -plocal-only --default-character-set=utf8mb4 < build/nb5/n10000.sql
docker exec notenest-db mariadb -uroot -plocal-only notenest_nb5 -e "ANALYZE TABLE music, user"
python scripts/nb5/nb5_capture.py --label n10000 --total 10000
docker exec -i notenest-db mariadb -uroot -plocal-only --default-character-set=utf8mb4 -t < scripts/nb5/phase0-explain.sql
k6 run -e MODE=search   -e BASE=http://localhost:8096 scripts/k6/nb5-search-baseline.js   # 콜드 1회 버림 후 웜 2회
k6 run -e MODE=nosearch -e BASE=http://localhost:8096 scripts/k6/nb5-search-baseline.js
```

생성 SQL 은 `DELETE FROM likes/music` 와 `@nb5.local` 사용자 삭제를 포함하지만, **`USE notenest_nb5` 로 고정**되어 있어 기존 `notenest` DB 에는 영향이 없다.
