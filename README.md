# NoteNest

**음원을 등록하고 입찰·낙찰·결제로 거래하는 음원 경매 서비스**

- **주요 흐름:** 곡 등록, 경매 입찰, 마감·낙찰, 결제, 음원 다운로드
- **개선 초점:** 대용량 음원·이미지 로딩으로 발생한 배치와 목록 조회 병목

| 구분 | 내용 |
|---|---|
| 팀 프로젝트 | 2024년 음원 경매 서비스 개발 |
| 담당 기능 | 경매 마감·낙찰, 결제 기한·차순위 승계, 이메일, 입찰·낙찰 내역, 사용자별 다운로드·횟수 관리 |
| 미디어 연동 | 이미지·음원 multipart 등록 및 수정 업로드 보완 |
| 개인 리팩터링 | 2026년 7월부터 경매 배치·곡 목록 조회 분석 및 개선 |

## 기술 스택

| 영역 | 기술 |
|---|---|
| Backend | Java 17 소스 호환, Spring Boot 3.2.5, Spring Security, JWT |
| Data | Spring Data JPA, QueryDSL, MariaDB 10.11 |
| 결제·메일 | Iamport REST Client, Spring Mail, Thymeleaf |
| 로컬 환경 | Docker Compose, smtp4dev |
| 검증 | JUnit 5, MockMvc, H2, k6, Hibernate SQL 로그, EXPLAIN |

- 개인 리팩터링 추가: QueryDSL 기반 동적 목록 프로젝션, k6 성능 검증
- 목록 조회 개선 측정·계약 테스트 실행 환경: Java 21.0.12

## 주요 기능

- **곡:** 이미지·음원 등록, 검색·필터·정렬, 좋아요
- **경매:** 입찰, 마감·낙찰 처리, 결제 기한 관리, 차순위 승계
- **거래 내역:** 입찰·낙찰 내역, 로그인 사용자별 다운로드·횟수 관리
- **회원·알림:** JWT 인증, 경매 진행에 따른 이메일 알림

## 설계와 개선

- 작업 구분: 팀 프로젝트 종료 후 개인 리팩터링
- 공통 측정 환경: 로컬 Windows 11, Docker MariaDB 10.11
- 미디어 시드: 곡당 음원 3MB·커버 이미지 300KB 더미

### 1. 경매 마감 대상만 조회해 대용량 미디어 로딩 축소

![경매 마감 대상 조회 개선](docs/images/NA1_경매_마감_대상_조회_Before_After.png)

**문제**

- 10초 주기 스케줄러에서 `findAll()`로 모든 곡을 읽고 Java에서 마감 여부 판정
- 곡 엔티티에 포함된 이미지·음원 LOB까지 로딩
- 곡 수 증가에 따라 전송량·힙 사용량 증가, 500곡 조건에서 OOM 발생

**개선**

- 마감 시각 조건을 DB 조회로 이동, 처리 대상의 UUID만 반환
- `auction_end_time` 인덱스 추가
- EXPLAIN의 `Using index`로 대상 UUID 조회의 커버링 인덱스 사용 확인
- 동일 시드의 낙찰 상태 분포를 비교해 경매 결과 회귀 검증

**검증 결과**

| 지표 | 개선 전 | 개선 후 |
|---|---:|---:|
| 100곡 기준 대상 식별 시간 | 5~8초 | 0.07초 |
| 대상 식별 시 힙 증가 | 1.3~2.2GB | 약 4MB |
| 500곡 기준 배치 완주 | OOM으로 0회 | 완주 |
| 낙찰 결과 분포 | 기준 분포 | 동일 |

- 조건: Java 17, 최대 힙 3.9GB, 전체 곡의 절반을 종료 대상으로 구성
- 시간 범위: 배치의 대상 식별 단계

**후속 개선: 마감 작업과 결제 후속 작업 분리**

- 마감 대상: `status=0`이면서 마감 시각이 지난 곡
- 결제 후속 대상: `PENDING` 입찰이 있는 곡
- 마감 처리 후 다음 사이클의 마감 대상에서 제외
- 차순위 승계: 동일 사용자의 하위 입찰을 건너뛰고 다른 사용자에게 승계, 최대 2명 유지
- 고정 Clock 테스트로 반복 실행 시 상태 전이·이메일 호출 무중복 확인

| 지표 | 분리 전 | 분리 후 |
|---|---:|---:|
| 최초 마감 후 반복 마감 대상 | 250건 | 0건 |
| 낙찰 상태·이메일 플래그 | 기준 결과 | 동일 |

- 별도 측정 조건: Java 21.0.12, 최대 힙 약 8GB, 500곡 중 절반 종료, 웜 5회

[배치 구현](src/main/java/com/notenest/service/BidServiceImpl.java) · [인덱스 추가 SQL](scripts/migration/phase1-01-add-auction-end-time-index.sql) · [1차 측정 기록](docs/measurements/phase1-after-step1.md) · [대상 분리 측정 기록](docs/measurements/n2-target-separation-before-after.md)

### 2. 곡 목록에서 음원 열 제외·좋아요 N+1 제거

![곡 목록 조회의 음원 로딩과 N+1 개선](docs/images/NA2_곡_목록_조회_Before_After.png)

**문제**

- 최신순 조회에 적용한 프로젝션을 `maxPrice` 필터 경로가 우회
- `Specification + findAll`의 엔티티 조회로 목록에 음원 LOB 재포함
- 곡마다 로그인 사용자의 좋아요 여부를 추가 조회하는 N+1 발생

**개선**

- 목록 전용 DTO로 커버 이미지는 유지하고 음원 열은 SELECT·응답에서 제외
- 공개 경매 목록의 검색·필터·정렬 분기를 QueryDSL DTO 프로젝션으로 통일
- 페이지 곡 UUID를 모아 좋아요 여부를 `IN` 쿼리 1회로 조회
- 계약 테스트 17개로 응답·정렬·검색·필터·실제 SQL의 동등성 검증

**검증 결과**

| 지표 | 개선 전 | 개선 후 |
|---|---:|---:|
| 응답시간 p90 | 2.65초 | 0.17초 |
| 요청당 응답 수신량 | 약 46MB | 약 4.1MB |
| 요청당 전체 SQL | 14회 | 4회 |
| 좋아요 여부 조회 | 곡마다 1회 | 페이지 전체 1회 |
| 목록 SELECT의 음원 열 | 포함 | 제외 |
| 커버 이미지 | 포함 | 동일 바이트 유지 |

- 조건: Java 21.0.12, 인증된 `maxPrice` 필터 요청, 페이지 10곡
- 시드·부하: 곡 100건 중 진행 중 50건, k6 10 VU × 30초 웜 2회차
- 비교 방식: 같은 머신에서 개선 전후 연속 측정, 전체 SQL은 요청 스레드 기준 집계

[DTO 프로젝션](src/main/java/com/notenest/repository/MusicRepositoryCustomImpl.java) · [좋아요 IN 조회](src/main/java/com/notenest/repository/LikeRepository.java) · [측정 기록](docs/measurements/n1-filter-maxprice-before-after.md)

## 검증 코드

| 검증 항목 | 테스트·스크립트 |
|---|---|
| 음원 제외·커버 보존·검색·필터·정렬·좋아요 매핑 | [MusicFilterContractTest](src/test/java/com/notenest/contract/MusicFilterContractTest.java) |
| 경매 마감·결제 기한·차순위 승계 | [AuctionLifecycleScenariosTest](src/test/java/com/notenest/batch/AuctionLifecycleScenariosTest.java) |
| 동일 사용자의 중복 입찰 처리 | [AuctionEndSameBidderPromotionTest](src/test/java/com/notenest/batch/AuctionEndSameBidderPromotionTest.java) |
| 고정 시각 반복 실행의 상태·이메일 무중복 | [AuctionBatchIdempotencyTest](src/test/java/com/notenest/batch/AuctionBatchIdempotencyTest.java) |
| 인증된 가격 필터 목록 부하 | [list-api-perf-maxprice.js](scripts/k6/list-api-perf-maxprice.js) |

## 로컬 실행

- 준비: JDK 21, Docker Compose
- 빌드 설정: Java 17 소스 호환
- 설정 확인: [기본 설정](src/main/resources/application.properties), [local 설정](src/main/resources/application-local.properties)
- [Compose 구성](docker-compose.yml): MariaDB `localhost:3311`, SMTP `localhost:2525`
- 로컬 메일 확인: smtp4dev 웹 UI `localhost:5001`

```powershell
docker compose up -d
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

- 애플리케이션: `localhost:8086`
- macOS·Linux: `./gradlew bootRun --args='--spring.profiles.active=local'`
- 결제 연동 설정: `IMP_CODE`, `IMP_API_KEY`, `IMP_API_SECRET`

**테스트**

- 경매 상태 테스트: H2 기반 테스트 픽스처·고정 Clock 사용
- 목록 계약 테스트: 실행 중인 로컬 MariaDB와 [곡 시드](scripts/seed/seed-music.sql) 필요
- 목록 계약 테스트는 시드 부재 시 건너뛰므로 실행 결과의 skipped 항목 확인
- 시드 적용·부하 실험은 전용 로컬 DB에서 수행

```powershell
.\gradlew.bat test
```

## 코드 구조

```text
src/main/java/com/notenest/
├── controller/  # 회원·곡·입찰·결제 API
├── service/     # 경매·결제·메일·다운로드 처리
├── repository/  # JPA·QueryDSL 조회
├── domain/      # 곡·입찰·회원 엔티티
├── dto/         # 요청·응답 및 목록 프로젝션
├── jwt/         # JWT 인증
├── config/      # 보안·쿼리·시각 설정
└── validator/   # 입력 검증
```
