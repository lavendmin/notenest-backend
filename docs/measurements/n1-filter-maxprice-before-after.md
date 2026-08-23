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
| SQL 집계 | `org.hibernate.SQL` DEBUG 로그의 http-nio 스레드 라인 수 (단독 요청 1건) |

### maxPrice=1000 → 100000 변경 사유
플랜 원 요청은 `maxPrice=1000`이었으나, 현재 시드 곡 가격이 11,000~15,000원이라
`maxPrice=1000`이면 결과 0건(응답 317B)이 되어 LOB 로딩·N+1 문제를 재현하지 못한다.
시드 가격대를 포함하는 `maxPrice=100000`으로 측정하며 before/after 동일 요청을 유지한다.
(소민님 확인, 2026-08-23)

## 표 A — maxPrice 필터 경로 before/after

| 측정 항목 | **before** (2026-08-23) | after |
|---|---|---|
| p(90) / p(95) | **2.71s / 2.75s** | _(미측정)_ |
| avg / med | 2.46s / 2.56s | _(미측정)_ |
| 처리량 | **3.82 req/s** (122 req / 30s) | _(미측정)_ |
| 30초 총 수신량 | **5.6 GB — 요청당 약 46 MB** | _(미측정)_ |
| 요청당 SQL | **14개** (유저 1 + 필터 1 + count 1 + 좋아요 카운트 × 10 = N+1) | _(미측정)_ |
| 실제 SELECT 열 | **`m1_0.audio`, `m1_0.image` 포함** (LOB를 SELECT 절에서 읽음) | _(미측정)_ |
| 단독 요청 (부하 없이 1건) | 0.79s | _(미측정)_ |

### before 원시 증거
- k6 웜 요약: [`raw/before-maxprice-k6-warm-summary.txt`](raw/before-maxprice-k6-warm-summary.txt)
- 단독 요청 Hibernate SQL 로그(SELECT에 audio/image 포함 증거): [`raw/before-maxprice-single-request-hibernate-sql.txt`](raw/before-maxprice-single-request-hibernate-sql.txt)
- 46MB 응답 프리뷰: [`raw/before-maxprice-single-response-preview.json`](raw/before-maxprice-single-response-preview.json)

### 해석
목록 10건에 audio·image 바이너리가 통째로 실려 요청당 ~46MB. SELECT 절에 실제
`m1_0.audio`가 찍혀 LOB를 DB에서 읽는 것이 로그로 증명됨(JSON 필드 제거만으로 주장하지 않음).
좋아요 카운트 10회(페이지 크기=10)로 N+1도 확인. 개선 목표: audio를 SELECT/응답에서 제거,
좋아요 조회를 IN 1회로 → 요청당 SQL 14 → 목표 3~4, 응답 크기 대폭 감소.

## 표 B — 무필터 1차 진단 (참고, 섞지 말 것)
`docs/measurements/phase1-before.md` 참조. size=20, 요청당 91MB, 요청당 SQL 24.
maxPrice 필터 경로(표 A)와 페이지 크기·요청 형태가 달라 직접 비교 금지.

## 표 C — S3 이후
_(미착수 — S3 전환 후 별도 측정)_
