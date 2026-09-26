# NB5 Phase 3 — 검색 API와 관련도 (Elasticsearch 8.11.1 + Nori)

작업일: 2026-09-26 / ADR-001 승인(Elasticsearch 8.11.1 + Nori 를 `searchTerm` 경로 P0 로 채택) / 기준 `4d2dc14`

## 0. 요약

- `searchTerm` 이 있는 `GET /api/music/filter` 는 이제 **검색 포트 → Elasticsearch 어댑터**를 탄다. 검색어가 없거나 공백이면 기존 QueryDSL 경로를 그대로 쓴다.
- **승인한 스파이크를 그대로 재현했다.** 실제 어댑터(Testcontainers ES)와 실행 중인 앱의 HTTP API 두 경로 모두에서 qrels v1 로 전체 nDCG@5 **0.8860**, T1 MRR@5 **1.0000**, T3 **0.8757** 이 나왔다. Phase 2 스파이크와 같은 값이다(통합 테스트가 소수 4자리까지 강제).
- ES 장애 시 검색어 요청은 **503**, 검색어 없는 목록은 **200**. 목, 실제 클라이언트(닫힌 포트), 실행 중인 앱에서 ES 를 멈춘 경우 모두 확인했다.
- 테스트: 기본 `./gradlew test` **206개 통과(ES 없이)**, 실제 ES 통합 `./gradlew nb5IntegrationTest` **11개 통과**.
- **main 반영 불가 상태다.** 색인은 기동 시 전체 재색인(옵션)으로만 채워진다. 등록·수정·입찰·좋아요·마감 이후의 갱신(Phase 4)이 없어서 지금은 검색 결과가 DB 보다 뒤처질 수 있다. CHECK 제약도 미해결로 유지한다.

채택 근거는 ADR-001 그대로다. LIKE + 질의 규칙 대비 조사·띄어쓰기·어순이 다른 한국어 검색에서 8승 17무 0패, T3 nDCG@5 0.7753 → 0.8757 을 확인했다. 개선의 대부분은 정확 일치·장르 매핑·상세 설명 포함 같은 **질의 규칙**에서 나왔다. 이 규칙은 엔진과 무관하며 본 구현에도 그대로 옮겼다.

## 1. 승인 설정 반영

| 결정 | 반영 |
|---|---|
| 1. JVM 힙 512MB 유지 | compose `ES_JAVA_OPTS=-Xms512m -Xmx512m`, 통합 테스트 컨테이너도 동일 |
| 2. 컨테이너 메모리 상한 1.5GiB | compose `mem_limit: 1536m`(재생성 후 `HostConfig.Memory=1610612736` 확인), 통합 테스트 컨테이너도 1.5GiB |
| 3. Phase 5 에서 최고 메모리 재기록 | 미수행 — Phase 5 항목으로 유지 |
| 4. ES 장애 시 searchTerm 경로 503 | `SearchUnavailableException` → 503 `{"message": "검색을 일시적으로 사용할 수 없습니다. 검색어 없이 목록을 볼 수 있습니다."}` |
| 5. 검색어 없는 QueryDSL 목록 정상 제공 | 검색어 없음·공백은 검색 포트를 거치지 않는다. 앱은 ES 없이도 기동한다(연결은 요청 시점) |
| 6. LIKE + 규칙 자동 폴백은 P1 | 구현하지 않음. QueryDSL 의 옛 `%keyword%` 분기는 도달할 수 없게 되어 제거했다(P1 에서 폴백을 만들 때 다시 설계) |

## 2. 구조

```
MusicController ──(searchTerm 있음)──▶ MusicServiceImpl ──▶ MusicSearchPort ──▶ ElasticsearchMusicSearchAdapter ──▶ ES
                 └─(없음/공백)────────▶ MusicServiceImpl ──▶ MusicRepositoryCustomImpl(QueryDSL) ──▶ MariaDB
                                          └─ 공통 후처리: 커버 URL 발급(MediaUrlIssuer), 좋아요 IN 조회 1회
```

| 파일 | 역할 |
|---|---|
| [MusicSearchPort](../../src/main/java/com/notenest/search/MusicSearchPort.java) | 검색어 경로 포트(조건·정렬·페이지 → `Page<MusicSummaryDTO>`, 커버는 키만) |
| [MusicSearchQueryFactory](../../src/main/java/com/notenest/search/MusicSearchQueryFactory.java) | 질의 조립. 가중치·정확 일치·장르 별칭은 Phase 2 사전 고정값, 필터·정렬·깊은 페이지 제한 |
| [ElasticsearchMusicSearchAdapter](../../src/main/java/com/notenest/search/ElasticsearchMusicSearchAdapter.java) | `_source` 로 DTO 조립(DB 재조회 없음). IOException·ElasticsearchException → 503 |
| [music-index.json](../../src/main/resources/elasticsearch/music-index.json) | Nori(mixed + 품사 필터 + 소문자), compact·lower 노멀라이저, strict 매핑 |
| [MusicSearchIndexer](../../src/main/java/com/notenest/search/MusicSearchIndexer.java)·[MusicSearchReindexService](../../src/main/java/com/notenest/search/MusicSearchReindexService.java) | 색인 재생성 + bulk 적재(_id = music_id), MariaDB 전체 → 색인 |
| ~~SearchIndexStartupRunner~~ | (리뷰 반영으로 [SearchIndexBootstrap](../../src/main/java/com/notenest/search/SearchIndexBootstrap.java) 로 대체 — 기동 시 항상 대조, 옵션이면 전체 재색인) |
| [MusicListSort](../../src/main/java/com/notenest/repository/MusicListSort.java) | 두 경로가 공유하는 Page 정렬 메타 표현 |

설정([application.properties](../../src/main/resources/application.properties)): `spring.elasticsearch.uris`(기본 `http://localhost:9201`), 연결 1s·응답 3s 타임아웃, `notenest.search.index=notenest-music`, `notenest.search.reindex-on-startup=false`.

의존성: `spring-boot-starter-data-elasticsearch` (Boot 3.2.5 관리). `dependencyInsight` 결과는 **spring-data-elasticsearch 5.2.5, elasticsearch-java 8.10.4** 다. 서버 8.11.1 보다 클라이언트가 한 마이너 낮은데, 공식 클라이언트는 같은 메이저의 상위 마이너 서버와 호환된다. 통합 테스트와 E2E 로 동작을 확인했다. Spring Data 저장소 추상화는 쓰지 않고 자동 구성된 `ElasticsearchClient` 를 쓴다.

## 3. API 계약 — 유지한 것과 의도적으로 바뀐 것

유지(테스트로 확인):
- 응답 JSON 필드(`MusicSummaryDTO`), Page 메타데이터(`totalElements`·`totalPages`·`sort.sorted`), 좋아요 IN 조회, 커버 URL(자기 객체 키, raw 키·점수 비노출), audio 부재
- 진행 중(status=0)만 노출. 장르는 대소문자 무시 정확 일치(MariaDB collation 과 같음). 해시태그는 태그별 부분 일치 AND. 가격은 현재가, 없으면 시작가. BPM 포함 범위, 키 정확 일치
- 명시 정렬 `latest`·`price`·`like` 의 순서(MariaDB 결과와 같은 기대 순서로 검증)
- 공백 검색어는 무검색, `%`·`_` 는 와일드카드가 아니다

의도적 변화:

| 변화 | 이전(LIKE) | 이후 | 근거 |
|---|---|---|---|
| `sortBy` 생략 시 정렬 | 최신순 | **관련도순**(`_score → created_at → music_id`) | ACTION_PLAN API 계약 |
| 매칭 방식 | 연속 부분 문자열 OR | Nori 토큰 기반 관련도(제목·판매자 3 > 부제 2 > 태그 1.5 > 장르·설명 1) + 정확 제목·판매자 +100 | ADR-001 |
| 상세 설명(`details`) | 검색 안 함 | 검색(가장 낮은 가중치) | 결정 1 |
| 한글 장르명 | 매칭 안 됨 | `발라드→balad` 등 명시 매핑 +2 | 결정 2 |
| 명시 정렬의 동점 | 정해지지 않음 | 요청 키 → `_score` → `music_id` | 안정적 tie-break |
| 깊은 페이지 | 제한 없음 | `from + size > 10,000` 이면 **400** | ES `max_result_window`, 검증한 최대 범위 |
| ES 장애 | 해당 없음 | **503**(검색어 경로만) | 승인 결정 4 |

**통합 시 주의:** 원 프론트엔드(`bidder-front/src/pages/Songs.js`)는 정렬 선택값을 항상 `sortBy` 로 보내고 기본값이 `latest` 다. 그래서 프론트를 바꾸지 않으면 검색 결과가 관련도순이 아니라 최신순으로 보인다. 품질 지표는 `sortBy` 를 생략한(관련도순) 결과다. 프론트 수정은 NB5 범위 밖이므로 한계로 기록한다.

## 4. 검증

### 4.1 기본 테스트 (`./gradlew test`, ES 없이) — 206개 통과

| 테스트 | 내용 |
|---|---|
| [MusicSearchQueryFactoryTest](../../src/test/java/com/notenest/search/MusicSearchQueryFactoryTest.java) (7) | 요청 JSON 으로 가중치·tie_breaker·정확 일치 boost(동일)·장르 별칭·필터 7종·정렬 5종·track_scores·페이지·깊은 페이지 400 |
| [MusicSearchDocumentTest](../../src/test/java/com/notenest/search/MusicSearchDocumentTest.java) (3) | 가격 기준, ISO 시각(초 고정), DTO 왕복(마감 시각 소수 초 보존) |
| [MusicFilterContractTest](../../src/test/java/com/notenest/contract/MusicFilterContractTest.java) (25, 실제 MariaDB) | 비검색 목록 계약 전부(기존 그대로) + 검색어 위임·응답 조립·503·400(포트는 목) |
| [SearchUnavailableContractTest](../../src/test/java/com/notenest/contract/SearchUnavailableContractTest.java) (1) | **실제 어댑터·클라이언트**를 닫힌 포트로 향하게 해 503, 무검색·공백 200, 5초 안에 응답 |
| [EvalDocumentsTest](../../src/test/java/com/notenest/search/eval/EvalDocumentsTest.java) (1) | Java 문서 생성이 파이썬 시드 생성기와 같은 UUID·등록 시각 |

`MusicFilterContractTest` 에서 LIKE 매칭 의미를 단언하던 검색어 테스트 8개는 삭제하지 않고 `MusicSearchApiIT` 로 옮겼다(실제 ES 로 검증). 필터와 섞여 있던 3개는 비검색 부분만 남겼다.

### 4.2 실제 Elasticsearch 통합 (`./gradlew nb5IntegrationTest`) — 11개 통과

- 이미지: 저장소의 `docker/elasticsearch/Dockerfile` 로 Testcontainers 가 매번 새로 띄운다(단일 노드·보안 끔·힙 512MB·1.5GiB). 로컬 `notenest-es`·기존 색인에 의존하지 않는다.
- [MusicSearchQualityIT](../../src/test/java/com/notenest/search/it/MusicSearchQualityIT.java): 평가 코퍼스 57곡 × 25질의. LIKE 기준선보다 전체·유형별로 높고 MRR 비퇴행임을 확인했고, 스파이크 수치를 소수 4자리로 재현했다([raw](raw/phase3-es-adapter-eval-only-quality.md)).
- [MusicSearchApiIT](../../src/test/java/com/notenest/search/it/MusicSearchApiIT.java) (10): 실제 MariaDB(`notenest_es_it`) 픽스처 → 전체 재색인 → HTTP. 관련도 기본 정렬(정확 제목 1위, 정확 판매자 3곡이 판매자명 부분 일치보다 위), 설명 검색, 필터 AND(장르 대소문자·해시태그 부분 일치 포함), 명시 정렬, 페이지·좋아요, 응답 필드·커버 URL·raw 키/점수 비노출, 마감 제외·공백·대소문자·와일드카드, BPM·키 결합, 깊은 페이지 400, 색인 부재 503 + 목록 200.

### 4.3 실행 중인 앱 E2E

- 품질: 앱(8096, `notenest_nb5` 57곡, 기동 시 재색인 57문서/1.1초) → `nb5_capture.py` → `nb5Eval`. 전체 0.8860 / T1 MRR 1.0000 / T3 0.8757 ([raw](raw/phase3-es-api-eval-only-quality.md), [순위](raw/phase3-es-api-results-eval-only.md)).
- 장애: 앱 실행 중 `docker compose stop notenest-es` → 검색어 요청 503(0.011s), 검색어 없음 200, 공백 검색어 200 ([raw](raw/phase3-es-outage-e2e.txt)).

## 5. 환경 이슈와 처리

| 이슈 | 처리 |
|---|---|
| Testcontainers 1.19.7(Boot 관리)의 docker-java 가 낮은 API 버전으로 요청 → Docker Engine 29.6(최소 API 1.40)이 400 | **테스트 전용**으로 `ext['testcontainers.version'] = '1.21.4'`(API 협상). 운영 의존성 영향 없음 |
| Testcontainers 정리용 Ryuk 이미지를 이 네트워크에서 받지 못함(Docker Hub CDN 응답 EOF, 재시도도 실패. hello-world 는 받아짐) | `nb5IntegrationTest` 에서만 `TESTCONTAINERS_RYUK_DISABLED=true`, JVM 종료 훅으로 컨테이너 정리(실행 후 잔존 0 확인). JVM 이 강제 종료되면 남을 수 있음 |

## 6. 한계·다음 단계

- **색인 갱신 없음(Phase 4):** 지금은 기동 시 전체 재색인만 있다. 등록·수정(BPM·키 포함)·삭제·입찰 현재가·좋아요·경매 마감 뒤 after-commit 갱신, 재시도·실패 기록, `sourceHash` 대조 복구, 2차 대조 변경 0건 확인이 남았다. 이것 없이는 main 반영이 불가하다.
- 전체 재색인은 고정 이름 색인을 지우고 다시 만드는 방식이라 그동안 검색이 503 이 된다. alias 교체는 P1 후보다.
- LIKE + 규칙 자동 폴백은 P1 후보(결정 6).
- ES 최고 메모리 재측정, API 수준 p50/p90 비교는 Phase 5.
- 원 프론트엔드가 `sortBy=latest` 를 항상 보내 관련도순이 화면에 적용되지 않는다(프론트 범위 밖).
- CHECK 제약 적용은 **미해결 유지**(Phase 1 §6). 적용 완료로 기록하지 않는다.
