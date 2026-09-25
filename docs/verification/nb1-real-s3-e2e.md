# NB1 실제 S3 E2E 검증

검증일: 2026-09-25 / 코드: `2e65430` 기준 / 버킷: `notenest-media` (ap-northeast-2, 범용·글로벌 네임스페이스, 퍼블릭 액세스 전부 차단, SSE-S3, 버전 관리·객체 잠금 끔)
실행 환경: 로컬 백엔드(Java 17, bootRun, 경매 스케줄러 끔) → 실제 S3. 자격증명은 앱 전용 IAM 사용자, 사용자 환경변수로만 주입.

> fake 저장소 테스트는 서비스의 보상 로직을 검증하고, 이 문서는 AWS 연동과 설정을 검증한다. 둘은 역할이 다르다.

## 1. 어댑터·IAM 최소 권한 (앱 코드 `S3ObjectStorage` 로 직접 호출)

| 확인 | 결과 |
|---|---|
| 없는 키 `head` | `Optional.empty` — 404 가 "없음"으로 매핑됨(ListBucket 권한이 있어 403 이 아니라 404) |
| `put` 후 S3 가 돌려준 SHA-256 | 로컬 계산값과 일치 |
| `head` 크기·Content-Type·SHA-256 | 9B, `text/plain`, 일치 |
| `delete` 후 `head` | empty |
| 허용하지 않은 `ListBuckets` | **403 AccessDenied** |
| 허용하지 않은 `GetBucketPolicy` | **403 AccessDenied** |

## 2. API 흐름 (curl)

| # | 시나리오 | 기대 | 결과 |
|---|---|---|---|
| 1 | 등록(커버 PNG·미리듣기 MP3·전체 데모 WAV, 커버는 선언 Content-Type 을 일부러 `application/octet-stream` 으로) | 201, 키·판별 형식 저장, LOB 없음 | 201, `image/png`·`audio/mpeg`·`audio/wav`, image·audio NULL |
| 2 | 비로그인 목록 | 커버 URL(1시간)만, base64·전체 데모 경로 없음 | `X-Amz-Expires=3600`, image 필드 없음, full-demo 0회 |
| 2 | 커버 URL GET | 200, 원본과 같은 바이트, 저장한 Content-Type | 200, 바이트 일치, `image/png` |
| 3 | 서명 없는 객체 URL(쿼리 제거) | 403 (private 버킷) | **403** |
| 3 | 서명 한 글자 변조 | 403 | **403** |
| 4 | 비로그인 상세 | 401 | 401 |
| 4 | 로그인 상세 | 미리듣기 URL(10분), audio·전체 데모 경로 없음 | `X-Amz-Expires=600`, 바이트 일치, audio 없음, full-demo 0회 |
| 5 | 입찰·결제 없는 사용자의 다운로드 | 403 | 403 |
| 5 | 판매자 다운로드 | 5분 URL JSON, 실제 형식 확장자 파일명 | `expiresInSeconds=300`, `X-Amz-Expires=300`, `NB1 S3 E2E(봄밤).wav`, GET 200·바이트 일치 |
| 5 | 다운로드 응답 헤더 | 한글 파일명 보존 | `attachment; filename="NB1_S3_E2E____.wav"; filename*=UTF-8''NB1%20S3%20E2E%28%EB%B4%84%EB%B0%A4%29.wav` |
| 6 | 커버 교체 | 새 키 연결, 옛 객체 삭제 | 200, 옛 커버 URL **404 NoSuchKey**, 새 커버 바이트 일치 |
| 7 | 곡 삭제 | DB 삭제 후 세 객체 삭제 | 200, 새 커버·미리듣기·전체 데모 URL 모두 **404** |

원시 출력: [raw/nb1-real-s3-e2e-run.txt](raw/nb1-real-s3-e2e-run.txt)

## 3. presigned URL 만료·재발급

| 확인 | 결과 |
|---|---|
| 발급 직후 GET | 200 |
| 발급 314초 뒤 같은 URL GET | **403 `AccessDenied` / `Request has expired`** |
| 만료 뒤 같은 곡 다운로드 API 재호출 | 새 URL 발급, GET 200 — 권한이 유지되는 동안 재발급 가능 |

presigned URL 은 만료 전에는 재사용·공유될 수 있다. 1회용 다운로드를 보장하지 않는다(NB1 결정).

## 4. 한글 원본 파일명

- 1번 등록에서 DB 의 `full_demo_original_name` 이 `U+FFFD`(�)로 저장됐다. Windows 용 curl 이 multipart 파일명을 CP949 바이트로 보낸 탓이다.
- 브라우저처럼 **UTF-8 파일명**으로 multipart 를 직접 구성해 다시 등록하자 `봄밤 데모.wav` 가 바이트 단위로 정확히 저장됐다(HEX 일치).
- 다운로드 파일명은 원본 파일명이 아니라 `제목(부제).확장자` 로 만들기 때문에, 위 깨짐은 사용자에게 보이는 파일명에 영향을 주지 않는다.

## 5. 정리 상태

- 테스트 곡 2건 삭제 후 버킷 객체 수 **0** (ListObjectsV2)
- 로컬 DB: music 500(N=500 시드), 객체 키 0 — 백필 전 상태 유지. 경매 스케줄러를 끄고 실행해 시드 데이터(입찰 상태 등)를 바꾸지 않았다.

## 검증하지 않은 것

- CORS: 프론트가 `fetch` 로 S3 URL 을 직접 읽을 때만 필요해 설정하지 않았다(`<img>`·`<audio>`·다운로드 이동은 CORS 불필요).
- 대용량(100MB 근처) 실업로드 시간·메모리: 이번 E2E 는 수 KB 파일이다.
- 네트워크 장애 중 실제 S3 오류 매핑: fake·mock 단위 테스트로만 검증했다.
