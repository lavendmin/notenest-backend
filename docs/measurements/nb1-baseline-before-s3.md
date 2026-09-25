# NB1 기준선 — S3 전환 직전 (LOB 저장 상태)

측정일: 2026-09-25 / 코드: `6104549` (브랜치 `feat/nb1-s3-media-storage`, NB2 금액 계약 반영 후)

> 목적: 이미지·음원을 DB LOB에서 S3로 옮기기 **직전** 상태를 NA2(목록)·NA1(경매 배치)의 기존 조건 그대로 다시 재서
> S3 이후 수치와 같은 조건으로 비교할 기준을 만든다.
> 기존 수치([n1-filter-maxprice-before-after.md](n1-filter-maxprice-before-after.md) 표 A after,
> [n2-target-separation-before-after.md](n2-target-separation-before-after.md) N=500 after)가 재현되는지 확인하고, 다르면 차이를 기록한다.
> 이 표는 무필터 1차 진단(91MB→5.3KB)이나 이전 세션 수치와 **섞지 않는다.** S3 이후 비교는 이 문서의 수치를 기준으로 한다.

## 공통 환경

| 항목 | 값 |
|---|---|
| 실행 환경 | 로컬 Windows 11, Docker MariaDB 10.11 (3311), smtp4dev (2525) |
| JVM | Java 21.0.12 (두 세션 bootRun 로그의 `Starting ... using Java 21.0.12`) |
| 앱 | Spring Boot 3.2.5, `./gradlew bootRun`, 포트 8086, 스케줄러 동시 가동 |
| 시드 | `scripts/seed/seed-music.sql` — 오디오 3MB·이미지 300KB 더미. NA2는 N=100, NA1은 N=500으로 **각각 재시드** |

## NA2 — 공개 곡 목록 (maxPrice 필터 경로)

조건은 N1 표 A와 같다: `GET /api/music/filter?searchTerm=&sortBy=latest&page=0&maxPrice=100000&minPrice=0`,
`bidder@test.local` JWT, 페이지 크기 10, k6 10 VU × 30s(`scripts/k6/list-api-perf-maxprice.js`), 콜드 1회 버리고 웜 2회차 채택.
앱 기동 후 catch-up 마감(50곡)이 끝난 뒤(status0=50/status1=50) 측정했다.

| 항목 | **NB1 기준선 (2026-09-25)** | 참고: N1 after (2026-08-23, `ec71b50`) |
|---|---|---|
| 요청당 크기 | **4,098,655 B (≈4.1MB)** | 4,098,693 B |
| 요청당 SQL (요청 스레드) | **4개** (유저 1 + 프로젝션 1 + count 1 + 좋아요 IN 1) | 4개 |
| 목록 SELECT의 음원 열 | **없음** (요청 스레드 SQL에 `audio` 0회) | 없음 |
| 단독 요청 1건 | 0.55s | 0.25s |
| 웜 p(90) / p(95) | **241.6ms / 266.7ms** | 170ms / 190ms |
| 웜 avg | 161.2ms | 130ms |
| 웜 처리량 | **37.9 req/s** (1,152 req/30s) | 42.87 req/s |
| 실패율 | 0% | 0% |

- **구조 지표는 재현됐다:** 크기·SQL 수·음원 열 제외가 같다. 크기가 38B 줄어든 것은 NB2에서 금액이 `10000.0` → `10000`(정수)으로 바뀌었기 때문이다.
- **시간 지표는 이전 세션보다 느리다:** 웜 p90이 170ms에서 242ms로 올랐다. 교차검증으로 한 번 더 돌린 3회차는 p90 253.4ms, 35.8 req/s였다. 콜드 회차는 p90 177.1ms, 41.8 req/s였다.
  목록 경로의 코드는 N1 이후 금액 타입 외에는 바뀌지 않았다. 측정 중에도 결제 후속 잡(PENDING 34곡)이 10초마다 LOB를 읽는 것은 이전 세션과 같다.
  GC·CPU·DB 버퍼를 측정하지 않아서 **세션 간 차이의 원인은 분리하지 못했다.** 그래서 S3 이후 비교는 **같은 날 같은 절차로 잰 이 기준선**과 한다.
- 커버 이미지는 여전히 응답에 base64로 실린다. 10건 × 300KB × base64 약 4/3 배라서 이것이 4.1MB의 대부분이다. S3 전환 후에는 커버 URL로 바뀌므로, 이 크기가 S3 이후 비교의 핵심 지표다.

원시: [k6 콜드](raw/nb1-baseline-na2-k6-cold.txt) · [k6 웜(채택)](raw/nb1-baseline-na2-k6-warm.txt) · [k6 3회차](raw/nb1-baseline-na2-k6-warm3.txt) ·
[단독 요청 SQL](raw/nb1-baseline-na2-single-hibernate-sql.txt) · [응답 프리뷰](raw/nb1-baseline-na2-single-response-preview.json) ·
[세션 JVM·배치 로그](raw/nb1-baseline-na2-session-jvm-and-batch.txt)

## NA1 — 경매 배치 사이클 (N=500)

조건은 N2 N=500과 같다. 재시드 직후 앱을 기동하고 catch-up 마감과 결제 후속 잡 웜 사이클의 `[BATCH]` 로그를 수집했다.
SQL 수는 직전 `[BATCH]` 줄 이후의 `Hibernate:` 문장 수다(측정 중 HTTP 요청 0건).

| 항목 | **NB1 기준선 (2026-09-25)** | 참고: N2 after 매칭 세션 (2026-08-23, `d24d7e3`) |
|---|---|---|
| catch-up 마감 | **146.8s** (targets=250, heap 84→2,959MB, SQL 1,241) | 110.4s (SQL 1,241) |
| 웜 마감 | targets=0, 5~7ms, SQL 1 | targets=0, 3~6ms, SQL 1 |
| **웜 결제 후속 (5회 평균)** | **51.3s** (targets=167) | 36.4s |
| 웜 결제 SQL | 첫 회 522, 이후 502 (= 1 + 167×3) | 521 |
| 웜 heap | 1.6~3.4GB | 1.8~3.4GB |
| OOM·실패 | 없음, failed=0 | 없음 |

웜 결제 후속 잡의 회차별 시간은 50,000·50,105·51,728·52,403·52,185ms로 평균 51,284ms다. 6회차는 48,214ms였다.

- **구조 지표는 재현됐다.** 마감 대상 250→0, 마감 SQL 1,241, 결제 대상 167, 곡당 SQL 3개가 같다. 불변식도 N2와 일치한다: `bid.status` PENDING 167 / FAILED 333 / NULL 748, `music.status` 0=250 / 1=250, 낙찰 메일 167, 무산 메일 83.
- **절대 시간은 N2 매칭 세션보다 약 1.4배 길다.** 웜 결제는 36.4s에서 51.3s, catch-up은 110.4s에서 146.8s로 늘었다. N2 문서에도 같은 코드가 20.5s와 36.4s로 관측된 세션 간 편차 기록이 있다. 이번에도 원인은 분리하지 못했다.
- 10초 주기 대비 웜 결제 사이클은 **여전히 5배 이상 초과한다.** 결제 대기 곡마다 `findById(Music)`가 음원 3MB와 이미지 300KB LOB를 매 사이클 읽는 비용이 남아 있다. S3 전환이 겨냥하는 잔존 비용이 이것이다.
- LOB 총량(N=500): 음원 1,500MB, 이미지 146.5MB.

원시: [배치 로그](raw/nb1-baseline-na1-n500-batch.txt) · [불변식·LOB 총량 쿼리](raw/nb1-baseline-na1-invariant-queries.txt)

## 업로드 크기 한도 (현재 계약의 제약)

multipart 설정이 없어서 Spring Boot 기본값(파일당 1MB, 요청당 10MB)이 적용된다.

| 요청 | 결과 |
|---|---|
| audio 500KB + image 100KB | 201 생성 |
| audio 2MB + image 100KB | **413**, `MaxUploadSizeExceededException` |

→ 현재 코드로는 1MB가 넘는 실제 음원을 **등록할 수 없다.** NB1에서 확정한 상한(전체 데모 100MB, 요청 120MB)은 multipart 설정과 S3 저장을 함께 바꿔야 성립한다.
DB 쪽 `max_allowed_packet`(16MB) 제약은 이 한도에 먼저 막혀서 관측하지 못했다.

원시: [업로드 재현](raw/nb1-baseline-upload-size-limit.txt)

## S3 이후 재측정 계획

- 같은 절차로 재시드 → 같은 요청 → NA2(크기·SQL·p90), NA1(웜 결제 사이클·catch-up)을 측정한다.
- S3 이후 수치는 **이 기준선과 같은 날 백투백**으로 재는 것을 원칙으로 한다. 세션 간 편차가 1.4~1.8배라 다른 날 수치끼리 비교하지 않는다.
