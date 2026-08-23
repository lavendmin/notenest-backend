# N1 목록 조회 — 기능 동등성 개선 결과 (필터 경로 before/after)

> 범위: 공개 경매 곡 목록 API `/api/music/filter`의 인증된 **maxPrice 필터 경로**
> (`getAllMusicByFilters` → Specification + `findAll` → 엔티티 통짜 로딩).
> 무필터 1차 진단(size=20, 91MB)과 **다른 표**로 분리한다. S3 이후 수치도 별도 표.

## 측정 조건 (before/after 동일 고정)

| 항목 | 값 |
|---|---|
| 대표 요청 | `GET /api/music/filter?searchTerm=&sortBy=latest&page=0&maxPrice=100000&minPrice=0` |
| 인증 | `bidder@test.local` / test1234 JWT |
| 시드 | `scripts/seed/seed-music.sql` N=100 (status0=50, status1=50), audio 3MB·image 300KB 더미, 곡당 입찰 0~5 |
| 페이지 크기 | 10 (요청에 size 미지정 → 컨트롤러 기본값) |
| 부하 | k6 10 VU × 30s, `scripts/k6/list-api-perf-maxprice.js`, 콜드 1회 버리고 웜 2회차 채택 |
| 환경 | 로컬 Windows 11, Docker MariaDB 10.11 (3311), Spring Boot 3.2.5 bootRun(8086), 스케줄러 동시 가동 |
| JVM | **Java 21.0.12** — before/after 두 bootRun 로그 모두 `Starting ... using Java 21.0.12` (원문: [`raw/backtoback-jvm-version.txt`](raw/backtoback-jvm-version.txt)). `sourceCompatibility=17`은 바이트코드 대상이지 측정 런타임이 아니다. 계약 테스트(`gradlew test`)도 Java 21에서 실행 |
| SQL 집계 | `org.hibernate.SQL` DEBUG 로그의 http-nio 스레드 라인 수 (단독 요청 1건) |
| **측정 방식** | **before/after 백투백** — 같은 머신 상태에서 재시드 후 옛 코드(`ada5174`) 측정 → 새 코드(`ec71b50`) 측정. 머신 상태 드리프트 confound 제거. |

### maxPrice=1000 → 100000 변경 사유
플랜 원 요청은 `maxPrice=1000`이었으나, 현재 시드 곡 가격이 11,000~15,000원이라
`maxPrice=1000`이면 결과 0건(응답 317B)이 되어 LOB 로딩·N+1 문제를 재현하지 못한다.
시드 가격대를 포함하는 `maxPrice=100000`으로 측정하며 before/after 동일 요청을 유지한다.
(소민님 확인, 2026-08-23)

## 표 A — maxPrice 필터 경로 before/after (백투백, 2026-08-23)

| 측정 항목 | **before** (`ada5174`, Specification+findAll) | **after** (`ec71b50`, QueryDSL 프로젝션+IN) | 개선 |
|---|---|---|---|
| p(90) / p(95) | 2.65s / 2.70s | **0.17s / 0.19s** | **약 15.6배** |
| avg | 2.38s | **0.13s** | — |
| 처리량 | 3.96 req/s (125 req/30s) | **42.87 req/s** (1297 req/30s) | **약 10.8배** |
| 요청당 크기 | 약 46 MB | **약 4.1 MB** | **약 11.2배 감소** |
| 요청당 SQL | 14개 (유저1+필터1+count1+좋아요×10 N+1) | **4개** (유저1+프로젝션1+count1+좋아요 IN 1) | 14→4 |
| 목록 SELECT의 음원 열 | **포함** (`m1_0.audio` — 필터 페이지 SELECT 가 엔티티 전열) | **제외** (프로젝션 SELECT 에 `audio` 없음), 커버 image만 | LOB 음원 제거 |
| 단독 요청 (부하 없이 1건) | 0.91s | **0.25s** | — |
| 실패율 | 0% | 0% | — |

**핵심**: 커버 이미지는 before/after 양쪽 모두 포함(기능 동등성 보존). 개선은 순수하게
**음원 LOB를 SELECT에서 제거 + 좋아요 N+1 제거**에서 나온다.

### 원시 증거 (raw/)
- before k6 웜: [`raw/backtoback-before-k6-warm.txt`](raw/backtoback-before-k6-warm.txt) · before SQL(audio 포함): [`raw/backtoback-before-single-hibernate-sql.txt`](raw/backtoback-before-single-hibernate-sql.txt)
- after k6 웜: [`raw/backtoback-after-k6-warm.txt`](raw/backtoback-after-k6-warm.txt) · after SQL(audio 0): [`raw/backtoback-after-single-hibernate-sql.txt`](raw/backtoback-after-single-hibernate-sql.txt) · after 응답 프리뷰: [`raw/backtoback-after-single-response-preview.json`](raw/backtoback-after-single-response-preview.json)
- 교차검증(게이트 최초 before, 백투백과 거의 동일 → baseline 안정성 확인): p90 2.71s / 3.82 req/s — [`raw/before-maxprice-k6-warm-summary.txt`](raw/before-maxprice-k6-warm-summary.txt)

### 해석
before: 목록 10건에 audio·image 바이너리가 통째로 실려 요청당 ~46MB, 목록 요청의 페이지 SELECT 절에
실제 `m1_0.audio`가 포함됨(LOB를 DB에서 읽는 증거). 좋아요 카운트 10회로 N+1 확인.
after: QueryDSL DTO 프로젝션으로 audio를 SELECT에서 제외하고 커버 image만 유지,
좋아요는 IN 1회. 요청당 SQL 14→4, 크기 46MB→4.1MB, p90 2.65s→0.17s.

> 집계 주의: raw 로그 창에는 동시 가동 중인 스케줄러(`scheduling-1`)의 `findById` 반복 SELECT 가 섞여 있다.
> 요청당 SQL 14/4 는 `nio-*-exec` 요청 스레드 기준 집계이며, 음원 열은 "목록 SELECT 에 포함→제외" 사실만
> 주장한다. 로그 전체의 `audio` 등장 횟수(예: 54회)는 스케줄러 쿼리가 섞인 값이라 목록 API 수치로 쓰지 않는다.

**정직 규율**: 개선은 QueryDSL 자체가 아니라 DTO 프로젝션(음원 열 제외)과 좋아요 IN 조회에서 나왔다.
같은 SELECT·WHERE 를 표현하면 Criteria·JPQL·QueryDSL 어느 도구든 DB 실행 비용은 본질적으로 비슷하다 —
QueryDSL 은 동적 조건의 타입 안전성과 프로젝션·count 재사용을 위한 작성 도구 선택이다.
무필터 1차 진단의 91MB→5.3KB(커버까지 제거)와 직접 비교하지 않는다.

### 계약 검증 범위와 알려진 메타데이터 변화
- 계약 테스트 17개(`MusicFilterContractTest`)로 고정: 커버 바이트 동일, 최신순 UUID 순서·총건수, 가격순 불변식
  (`currentHighestBid` DESC nulls last → `startingPrice` DESC)과 **동가 tie-break·nulls-last 예상 UUID 순서(전용 픽스처: 시작가·최고가 상이, 롤백)**,
  **좋아요순 예상 UUID 순서와 `likedByUser` true/false 매핑(픽스처: 서로 다른 likeCount + bidder 실제 좋아요, 롤백)**,
  `sortBy` 단독 정렬, **검색(제목·작곡가 닉네임·부제·장르·해시태그)·장르·해시태그·가격 경계의 결과 집합을 UUID 로 비교**,
  음원이 JSON·SQL SELECT 양쪽에 없음, 좋아요 쿼리 수 페이지 크기 무관, **Page 메타데이터 `sort.sorted=true`**.
- Page 메타데이터: 정렬 정보를 담은 Pageable 을 `PageImpl` 에 넘겨 기존 필터 경로의 `sort.sorted=true` 계약을 유지한다.
  단, 무필터 요청은 이전에 정렬 정보 없는 Pageable(`sort.sorted=false`)을 반환했는데 이제 경로가 통일되어
  `sorted=true` 로 바뀐다 — content 는 동일하며 프론트는 `content`·`totalPages` 만 사용한다. 의도된 메타데이터 변화로 기록한다.

## 표 B — 무필터 1차 진단 (참고, 섞지 말 것)
`docs/measurements/phase1-before.md` 참조. size=20, 요청당 91MB, 요청당 SQL 24.
maxPrice 필터 경로(표 A)와 페이지 크기·요청 형태가 달라 직접 비교 금지.

## 표 C — S3 이후
_(미착수 — S3 전환 후 별도 측정)_
