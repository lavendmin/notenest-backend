# Phase 1 — before 측정 기록 (개선 전 베이스라인)

측정일: 2026-07-19

## 측정 환경 (조건 고정 — after 측정도 동일 조건으로)

| 항목 | 값 |
|---|---|
| 실행 환경 | 로컬 Windows 11, RAM 15.6GB, 로컬 Docker MariaDB 10.11 (포트 3311) |
| JVM | Java 17.0.7, 최대 힙 3.9GB (기본값, RAM의 1/4) |
| 앱 | Spring Boot 3.2.5, `./gradlew bootRun`, 포트 8086 |
| 시드 데이터 | `scripts/seed/seed-music.sql` — 오디오 3MB/이미지 300KB 더미, 곡의 절반은 마감 과거(종료 처리 대상), 곡당 입찰 0~5건 |
| 쿼리 수 집계 | `org.hibernate.SQL` DEBUG 로그의 스레드별 라인 수 (스케줄러=scheduling-1, API=nio-exec) |
| 사이클 시간·힙 | `BidServiceImpl.checkAuctionEnd`의 `[PERF]` 로그 (측정 코드 커밋됨) |

## 1. 스케줄러 (`checkAuctionEnd`, 10초 주기)

### N=100 (종료 대상 50곡)

| 측정 항목 | 값 |
|---|---|
| 첫 사이클 (낙찰 처리 50건 + 동기 이메일 발송 포함) | **28.1초** |
| 웜 사이클 5회 (10.5 / 11.4 / 9.0 / 8.6 / 10.1초) | **평균 9.9초** |
| 사이클당 SQL | **SELECT 85개** (findAll 1 + 종료곡 50건 매 사이클 재방문) |
| 힙 사용량 | 사이클 시작 62MB → findAll 직후 **1.4~2.3GB** |

**해석**: 곡 100건에서 이미 사이클 시간(9.9초) ≈ 실행 주기(10초) — **스케줄러 포화 상태.**
사이클이 끝나자마자 다음 사이클이 시작되어 사실상 쉬지 않고 전체 곡 바이너리를 로딩 중.
첫 사이클 28.1초는 `@EnableAsync` 부재로 인한 동기 이메일 발송이 낙찰 처리를 직렬 블로킹한 것 포함 (Phase 4 소재).

### N=500 (종료 대상 250곡)

| 측정 항목 | 값 |
|---|---|
| 완주한 사이클 | **0회** |
| 결과 | **`OutOfMemoryError: Java heap space` — 사이클 완주 불가** |

OOM 스택트레이스 발생 지점 (원인 직접 증빙):

```
Caused by: java.lang.OutOfMemoryError: Java heap space
    at java.io.ByteArrayOutputStream.write
    at org.hibernate.type.descriptor.java.DataHelper.extractBytes   ← blob 추출 중 힙 소진
    at org.hibernate.type.descriptor.java.PrimitiveByteArrayJavaType.wrap
    (musicRepository.findAll() 경유)
```

**해석**: 곡 500건 × 3.3MB ≈ 1.7GB 원본에 JPA 영속성 컨텍스트·드라이버 버퍼 오버헤드가 붙어
기본 힙(3.9GB)을 초과. 10초마다 재시도→재OOM 반복. **곡 수가 늘면 힙 증설로도 못 버티는 구조**
(선형 증가) — 데이터 모델 자체가 원인임을 보여주는 핵심 수치.

### N=1000

측정 불가 — N=500에서 이미 OOM이므로 동일 사유로 생략 (a fortiori).

## 2. 곡 목록 API (`GET /api/music/filter?page=0&size=20`)

측정 조건: N=100 시드, k6 10 VU × 30초. 스케줄러 동시 가동 상태(운영과 동일 조건).

**공식 채택값 (본인 직접 실측, 2026-07-19):**

| 측정 항목 | 값 |
|---|---|
| p(90) / p(95) | **9.18초** / 10.24초 |
| avg / med | 7.1초 / 7.24초 |
| 처리량 | **1.25 req/s** (10 VU가 30초간 44회밖에 조회 못 함) |
| 30초 총 수신량 | **4.1GB — 요청 1건당 약 91MB** (곡 20건 목록 JSON에 오디오·이미지 바이너리가 통째로 실림) |
| 요청 1건당 SQL | **24개** (유저 조회 1 + 페이지 조회 + count + 곡 20건 × 좋아요 카운트 = N+1) |
| 단독 요청 (부하 없이 1건) | 1.9~2.0초 |

참고: 최초 측정 시도에서는 p(90) 18.33초/0.69 req/s가 관측됨 (시드 직후 GC 압박 상태).
동일 스크립트·동일 데이터에서 머신 상태에 따라 p(90) 9~18초 범위로 변동 — 보수적으로 낮은 쪽(9.18초)을 채택.

**해석**: 목록 20건에 바이너리가 딸려와 요청당 91MB. N+1(좋아요 카운트 20회)도 확인 — 개선 목표 "24쿼리 → 2~3쿼리".

## 3. 남은 before 항목 (세션 2 진행 전 선택)

- [ ] 이메일 지연 주입 측정 (smtp4dev 지연 설정 → 종료 5곡 사이클 시간): 첫 사이클 28.1초로 동기 발송 영향은 이미 관측됨. 정밀 수치가 필요하면 Phase 4 착수 시 측정.

## 불변식 기준값 (개선 후 낙찰 처리 결과가 이와 동일해야 함)

N=100 시드 → 스케줄러가 낙찰 처리를 완료한 뒤의 분포 (2026-07-19 확보):

| 대상 | 분포 |
|---|---|
| `bid.status` | **PENDING 34 / FAILED 68 / NULL 148** (NULL = 진행 중 곡의 입찰. 생성 시 status 미설정은 알려진 이슈 — Phase 3) |
| `music.status` | 진행(0) 50 / 낙찰 처리됨(1) 50 |

확인 쿼리: `SELECT IFNULL(status,'NULL'), COUNT(*) FROM bid GROUP BY status;`

## after 측정 시 재현 절차 (동일 조건 필수)

1. 앱 정지 → `docker exec -i notenest-db mariadb -uroot -plocal-only notenest < scripts/seed/seed-music.sql` (CALL 숫자로 N 조정)
2. `./gradlew bootRun` → `[PERF]` 로그로 사이클 6회 수집 (첫 사이클 별도 기록, 웜 5회 평균)
3. `k6 run scripts/k6/list-api-perf.js` 2회 (2회차 채택)
4. 단독 요청 1건의 스레드별 `org.hibernate.SQL` 라인 수로 요청당 쿼리 집계
5. 불변식: `SELECT status, COUNT(*) FROM bid GROUP BY status` 분포를 before와 비교
