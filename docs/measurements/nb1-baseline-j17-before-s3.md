# NB1 기준선 (Java 17) — S3 전환 직전, LOB 저장 상태

측정일: 2026-09-25 오후 / 코드: `83353d2` (브랜치 `feat/nb1-s3-media-storage`) / **JVM: Java 17.0.12 (Gradle toolchain 고정)**

> **S3 이후 수치는 이 문서와 비교한다.**
> 같은 날 오전에 잰 [Java 21 기준선](nb1-baseline-before-s3.md)은 PATH에 우연히 잡힌 Java 21로 측정한 이전 실험이다. 최종 비교에 쓰지 않는다.
> 프로젝트의 의도된 버전이 Java 17이라 `a6e7b0f`에서 toolchain을 17로 고정했고, 이 문서는 그 뒤에 같은 절차로 다시 잰 결과다.
>
> 코드는 Java 21 기준선(`6104549`) 이후 권한 결함 수정(`c4b7b8f`)과 테스트 정비가 반영된 상태다.
> 권한 수정은 목록 SQL 수(4개)와 배치 경로를 바꾸지 않았다. 따라서 이 기준선과 S3 이후를 비교하면 차이는 S3 전환에서만 나온다.
> 무필터 1차 진단(91MB→5.3KB)이나 이전 세션 수치와 섞지 않는다.

## 공통 환경

| 항목 | 값 |
|---|---|
| 실행 환경 | 로컬 Windows 11, Docker MariaDB 10.11 (3311), smtp4dev (2525) |
| JVM | Java 17.0.12. 두 세션 bootRun 로그 모두 `Starting ... using Java 17.0.12`. toolchain이 PATH의 Java 21과 무관하게 17을 사용 |
| 앱 | Spring Boot 3.2.5, `./gradlew bootRun`, 포트 8086, 스케줄러 동시 가동 |
| 시드 | `scripts/seed/seed-music.sql` — 오디오 3MB·이미지 300KB 더미. NA1은 N=500, NA2는 N=100으로 **각각 재시드** |

## NA2 — 곡 목록 (maxPrice 필터 경로)

조건은 N1 표 A와 같다.
- 요청: `GET /api/music/filter?searchTerm=&sortBy=latest&page=0&maxPrice=100000&minPrice=0`
- 인증: `bidder@test.local` JWT
- 페이지 크기 10
- 부하: k6 10 VU × 30s (`scripts/k6/list-api-perf-maxprice.js`), 콜드 1회를 버리고 웜 2회차를 채택
- 앱 기동 후 catch-up 마감(50곡)이 끝난 뒤(status0=50, status1=50) 측정

| 항목 | **NB1 기준선 (Java 17)** |
|---|---|
| 요청당 크기 | **4,098,655 B (≈4.1MB)** |
| 요청당 SQL (요청 스레드) | **4개** (유저 1 + 프로젝션 1 + count 1 + 좋아요 IN 1) |
| 목록 SELECT의 음원 열 | **없음** (요청 스레드 SQL에서 `audio` 0회. `audio`를 읽는 SQL은 모두 `scheduling-1` 스레드) |
| 단독 요청 1건 | 0.51s |
| 웜 p(90) / p(95) | **153.7ms / 166.5ms** |
| 웜 avg | 118.9ms |
| 웜 처리량 | **45.3 req/s** (1,373 req/30s) |
| 실패율 | 0% |

- 교차검증으로 돌린 3회차는 p90 159.2ms, 44.3 req/s였다. 콜드 회차는 p90 153.0ms, 45.7 req/s였다. **세 회차가 ±4% 안에 모인다.**
- 응답 4.1MB의 대부분은 base64로 실린 커버 이미지다(10건 × 300KB × 약 4/3). S3 전환 후에는 커버가 URL로 바뀌므로, 이 크기가 S3 이후 비교의 핵심 지표다.

원시: [k6 콜드](raw/nb1-baseline-j17-na2-k6-cold.txt) · [k6 웜(채택)](raw/nb1-baseline-j17-na2-k6-warm.txt) · [k6 3회차](raw/nb1-baseline-j17-na2-k6-warm3.txt) ·
[단독 요청 SQL](raw/nb1-baseline-j17-na2-single-hibernate-sql.txt) · [응답 프리뷰](raw/nb1-baseline-j17-na2-single-response-preview.json) ·
[세션 JVM·배치 로그](raw/nb1-baseline-j17-na2-session-jvm-and-batch.txt)

## NA1 — 경매 배치 사이클 (N=500)

조건은 N2 N=500과 같다. 재시드 직후 앱을 기동하고, catch-up 마감과 결제 후속 잡 웜 사이클의 `[BATCH]` 로그를 수집했다.
SQL 수는 직전 `[BATCH]` 줄 이후의 `Hibernate:` 문장 수다(측정 중 HTTP 요청 0건).

| 항목 | **NB1 기준선 (Java 17)** |
|---|---|
| catch-up 마감 | **76.2s** (targets=250, heap 165→2,500MB, SQL 1,241) |
| 웜 마감 | targets=0, 2~3ms, SQL 1 |
| **웜 결제 후속 (5회 평균)** | **24.95s** (targets=167) |
| 웜 결제 SQL | 첫 회 522, 이후 502 (= 1 + 167×3) |
| 웜 heap | 1.6~2.7GB |
| OOM·실패 | 없음, failed=0 |

웜 결제 후속 잡의 회차별 시간은 26,019·24,856·24,536·24,893·24,449ms로 평균 24,951ms다. 6회차는 24,716ms였다. 회차 간 편차는 ±5% 안이다.

- **불변식이 N2와 일치한다.** `bid.status` PENDING 167 / FAILED 333 / NULL 748, `music.status` 0=250 / 1=250, 낙찰 메일 167, 무산 메일 83.
- 10초 주기 대비 웜 결제 사이클은 **여전히 약 2.5배 초과한다.** 결제 대기 곡마다 `findById(Music)`가 음원 3MB와 이미지 300KB LOB를 매 사이클 읽는 비용이 남아 있다. S3 전환이 겨냥하는 잔존 비용이 이것이다.
- LOB 총량(N=500): 음원 1,500MB, 이미지 146.5MB.

원시: [배치 로그](raw/nb1-baseline-j17-na1-n500-batch.txt) · [불변식·LOB 총량 쿼리](raw/nb1-baseline-j17-na1-invariant-queries.txt)

## 업로드 크기 한도 (현재 계약의 제약)

multipart 설정이 없어 Spring Boot 기본값(파일당 1MB, 요청당 10MB)이 적용된다. Java 21 측정 때와 결과가 같다.

| 요청 | 결과 |
|---|---|
| audio 500KB + image 100KB | 201 생성 |
| audio 2MB + image 100KB | **413**, `MaxUploadSizeExceededException` |

원시: [업로드 재현](raw/nb1-baseline-j17-upload-size-limit.txt)

## 참고 — 오전 Java 21 실험과의 차이 (원인 미분리)

| 지표 | 오전 Java 21 (`6104549`) | 오후 Java 17 (`83353d2`) |
|---|---|---|
| NA2 웜 p90 | 241.6ms | 153.7ms |
| NA1 웜 결제 사이클 | 51.3s | 24.95s |
| NA1 catch-up | 146.8s | 76.2s |
| 앱 기동 시간 | 53.4s | 4.9~5.8s |

구조 지표(응답 크기, SQL 수, 대상 수, 불변식)는 두 측정에서 같다. 시간 지표만 크게 다르다.
그러나 이 차이를 **JVM 버전 효과로 해석하지 않는다.** 측정 시각이 다르고, 오전 세션은 앱 기동부터 약 10배 느렸다. 호스트 상태가 달랐다는 신호다.
GC·CPU·DB 버퍼는 측정하지 않았다. 그래서 JVM 영향과 세션 영향을 분리하지 못했다.
S3 이후 측정은 **이 문서와 같은 JVM(17)·같은 절차로, 가능하면 같은 날 연달아** 잰다.
