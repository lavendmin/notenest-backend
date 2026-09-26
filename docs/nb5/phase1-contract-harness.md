# NB5 Phase 1 — 검색 계약·평가 하네스·BPM/키 스키마

작업일: 2026-09-26 / 기준: `63a5d64`(Phase 0) / JVM: Java 17.0.12 / DB: MariaDB 10.11.18(`notenest-db`)

Elasticsearch 의존성·Docker 서비스는 추가하지 않았다. 기존 `notenest`·`notenest_contract` DB는 초기화하지 않았다.
측정은 이번 작업에서 만든 격리 스키마 `notenest_nb5`에서만 했다(§6의 사정으로 한 번 재생성).

## 0. 요약

- **qrels v1 확정**: 25개 질의 × 57곡을 완전 판정했다(관련 판정 178쌍: 3등급 95 / 2등급 33 / 1등급 50, 나머지는 0). 판정 규칙은 [eval/qrels-guidelines.md](eval/qrels-guidelines.md)에 있다. 규칙 가운데 코퍼스 속성만으로 계산되는 부분(정확 제목·정확 판매자·구조화 조건·장르)은 `QrelsIntegrityTest`가 다시 계산해 파일과 대조한다.
- **평가 하네스**: nDCG@5·MRR@5·Top-5 적중률·judged@5 계산기와 유형별 집계, 캡처 결과 채점 CLI(`./gradlew nb5Eval`). 지표 정의식은 손으로 계산한 값으로 테스트했다.
- **현재 LIKE 기준선(qrels v1, 평가 코퍼스 57곡)**

| 범위 | 질의 | nDCG@5 (Phase 0 LIKE) | nDCG@5 (Phase 1 LIKE + BPM·키 필터) | MRR@5 | Top-5 적중률 (Phase 1) |
|---|---|---|---|---|---|
| 전체 | 25 | 0.4931 | **0.5084** | — | 0.5200 |
| T1 곡명 | 9 | 0.5472 | 0.5472 | **0.3889** | 0.6667 |
| T2 조건 | 6 | 0.3087 | 0.3726 | — | 0.3667 |
| T3 분위기 | 6 | 0.5079 | 0.5079 | — | 0.3750 |
| T4 공급자 | 4 | 0.6254 | 0.6254 | — | 0.6375 |

  Phase 1 에서 달라진 질의는 Q13 하나다. 이전에는 BPM·키 파라미터가 무시돼 11건이 나왔고(nDCG 0.6164), 이제 조건을 모두 만족하는 5곡만 나온다(nDCG 1.0000). 나머지 24개 질의의 결과 순서와 건수는 Phase 0 과 같다. **Phase 2·3 의 비교 기준선은 Phase 1 LIKE(0.5084 / MRR 0.3889)** 로 둔다. 원시: [phase0](raw/phase0-like-eval-only-quality.md), [phase1](raw/phase1-like-eval-only-quality.md).
- **BPM·키**: 스키마·검증·등록/수정·목록 필터·상세 응답·시드·마이그레이션(nb5-01)·리허설을 모두 마쳤다. 다만 측정 스키마에 nb5-01 을 적용할 때 **CHECK 제약 추가 단계만 로컬 Docker 바인드 마운트 오류로 실패했다**(§6, 미해결).
- **테스트**: `./gradlew test` 111개 → **197개 전부 통과**(신규 86개).

## 1. 결정 반영

| 결정 | 반영 위치 |
|---|---|
| 1. `details`를 신규 검색 대상으로, 제목·부제보다 낮은 가중치 후보 | qrels 는 **필드 위치를 등급에 반영하지 않는다**. 설명에만 적힌 특성도 곡이 그 특성을 가지면 같은 등급이다. 가중치는 랭킹 변수이고 qrels 로 검증한다. 현재 LIKE 계약(설명 미검색)은 특성화 테스트로 고정했다. Phase 3 검색 엔진 경로에서 의도적으로 바꾼다 |
| 2. 한글 장르명 → 저장 코드 명시 매핑 | 계약: `발라드→balad`, `힙합→hiphop`, `트로트→trot`, `팝·케이팝→pop`. qrels 에 반영했다(Q11, Q25). **코드 구현은 Phase 3 검색 경로에서** 한다. LIKE 기준선은 바꾸지 않는다(기준선이 움직이면 비교가 무의미해진다) |
| 3. BPM 40~250 정수, 첫 입찰 후 수정 허용, 변경 시 색인 갱신 대상 | `Bpm`, 등록·수정 검증, `MusicAttributesFlowTest.updateAllowedAfterFirstBid`. 색인 갱신 대상 목록에 "곡 수정(BPM·키 포함)"을 추가한다(Phase 4) |
| 4. musicalKey 24개 enum, 명확한 영문만 정규화, 한글 음이름 제외, `AM` 등 모호 표기 거부 | `MusicalKey`(파서 포함), `MusicAttributesTest` 53개 케이스 |
| 5. 정확 제목과 정확 판매자 충돌 시 둘 다 같은 높은 등급 | Q32 `윤슬`: W01(제목)과 판매자 `윤슬`의 7곡이 모두 3등급. 테스트가 "T4 3등급 = 정확 판매자 ∪ 정확 제목"을 강제한다 |

## 2. qrels v1

- 파일: [eval/qrels-v1.jsonl](eval/qrels-v1.jsonl) (적히지 않은 곡은 0), 규칙: [eval/qrels-guidelines.md](eval/qrels-guidelines.md)
- 등급 분포

| 유형 | 3 | 2 | 1 | 판정 쌍 |
|---|---|---|---|---|
| T1 곡명 | 8 | 10 | 13 | 31 |
| T2 조건 | 23 | 6 | 5 | 34 |
| T3 분위기 | 42 | 17 | 20 | 79 |
| T4 공급자 | 22 | 0 | 12 | 34 |

- 작성 과정에서 규칙과 어긋난 2건을 발견해 **평가를 돌리기 전에** 고쳤다. Q07 의 D03 은 부제·설명 근거가 없어 1 → 0 으로, Q30 의 S07 은 공백을 무시하면 부제에 `새벽공방`이 들어 있어 0 → 1 로 바꿨다. 이후 등급 변경은 없다.
- **확인이 필요한 점**: 이 qrels 는 규칙에 따라 작성자(Claude)가 판정했다. ACTION_PLAN 의 "사람이 먼저 확정한다"를 충족하려면 **소민님의 검토·승인**이 필요하다. 특히 T3 의 의미 판정(예: Q21 에서 `#dance` 힙합을 1등급으로 둔 것)은 사람의 판단 영역이다. 검토 후 바꿀 곳이 있으면 Phase 2 전에 `qrels-v1` 을 수정한다. Phase 2 결과를 본 뒤에는 v2 로만 바꾼다.
- 공정성: Phase 0 에서 LIKE 결과를 먼저 봤다. 이를 감안해 등급은 규칙과 코퍼스 속성으로만 정했고, 기계적으로 계산되는 부분은 테스트로 강제했다.

## 3. 평가 하네스

| 파일 | 역할 |
|---|---|
| [RankingMetrics](../../src/test/java/com/notenest/search/eval/RankingMetrics.java) | nDCG@k(이득 2^g−1, 할인 log2(i+1)), RR@k(최고 등급 곡 기준), Top-k 적중률, judged@k |
| [SearchEvaluation](../../src/test/java/com/notenest/search/eval/SearchEvaluation.java) | run(qid → 곡 id 순위)을 채점해 전체·유형별 평균, MRR 은 T1 만. 마크다운 출력 |
| [SearchEvalCli](../../src/test/java/com/notenest/search/eval/SearchEvalCli.java) + `nb5Eval` 태스크 | 캡처 JSON 을 채점해 `docs/nb5/raw/{label}-quality.md` 로 기록 |
| [EvalData](../../src/test/java/com/notenest/search/eval/EvalData.java)·[Qrels](../../src/test/java/com/notenest/search/eval/Qrels.java) | 코퍼스·질의·qrels 로더 |
| [scripts/nb5/nb5_capture.py](../../scripts/nb5/nb5_capture.py) | 질의셋을 실제 API 로 실행해 run 을 만든다(`--prefix` 추가) |

- 하네스는 검색 후보와 무관하게 HTTP 계약(`/api/music/filter`) 결과만 받는다. 그래서 LIKE·FULLTEXT·Elasticsearch 를 같은 기준으로 채점할 수 있다. 계산기는 test 소스에 두었다. Phase 3 의 Elasticsearch 통합 테스트에서도 그대로 쓸 수 있다.
- 품질은 평가 코퍼스 57곡만 적재한 상태에서 잰다(judged@5 = 1.0 확인). 10,000곡 상태는 filler 를 판정하지 않았으므로 성능 측정에만 쓴다.
- 실행 순서: `nb5_capture.py --prefix phase1-like-results --label eval-only --total 57` → `./gradlew nb5Eval -Prun=docs/nb5/raw/phase1-like-results-eval-only.json -Plabel=phase1-like-eval-only`

## 4. 기존 목록 API 기능 동등성 테스트 보강

[MusicFilterContractTest](../../src/test/java/com/notenest/contract/MusicFilterContractTest.java)(실제 MariaDB `notenest_contract`)에 11개를 추가했다. 픽스처에는 BPM·키·설명을 더했다.

- 검색 경로(Phase 3 에서 검색 엔진으로 옮길 때 지켜야 할 계약): 검색어 + 가격·장르·해시태그 AND 결합 / 검색어 + 명시 정렬(최신·가격·좋아요) / 검색 결과의 페이지 메타데이터·좋아요 여부 / 마감 곡 제외 / 공백 검색어 = 무검색, 대소문자 무시 / `%`·`_` 는 문자 그대로 / **현재 계약: 설명 미검색**(결정 1 에 따라 Phase 3 에서 바뀔 예정임을 테스트 이름에 밝힘)
- BPM·키 필터: 포함 범위·한쪽 경계·값 없는 곡 제외 / 영문 표기와 enum 이름이 같은 결과 / 검색어·가격·정렬과 결합 / 잘못된 값은 400 (`bpmMin=39`, `bpmMax=251`, min > max, `AM`, `라단조`, `90.5`, `fast`)
- 기존 18개 테스트는 수정 없이 그대로 통과한다. 비검색 경로 계약은 그대로다.

## 5. BPM·musicalKey 계약

### 5.1 API

| 경로 | 추가 | 오류 |
|---|---|---|
| `POST /api/music/create` (music JSON) | `bpm`(정수, 선택), `musicalKey`(문자열, 선택) | 범위 밖·소수·모호/미지원 키 → 400, **파일을 하나도 올리지 않는다** |
| `PUT /api/music/{uuid}` | 같음. 보내지 않으면 유지. 첫 입찰 후에도 수정 가능 | 400, DB 변경 없음 |
| `GET /api/music/{uuid}` | 응답에 `bpm`, `musicalKey`(enum 이름). 값이 없으면 필드 생략(non_null) | — |
| `GET /api/music/filter` | `bpmMin`·`bpmMax`(포함 범위), `musicalKey`(정확 일치, 영문 표기 또는 enum 이름) | 400 + `{"message": ...}` — 이전처럼 조용히 무시하지 않는다 |

- 목록 응답(`MusicSummaryDTO`)에는 BPM·키를 추가하지 않았다. 목록 JSON 계약을 바꾸지 않기 위해서다.
- 등록·수정에서 값을 **지우는** 요청은 P0 에 없다(null = 변경 없음).
- 목록 경로는 `MusicListCondition`(조건 객체)으로 정리했다. Phase 3 에서 검색 엔진 어댑터에 같은 조건을 넘기기 위한 이음새다. 생성 SQL 은 BPM·키 조건이 없으면 이전과 같다.

### 5.2 정규화 규칙 ([MusicalKey](../../src/main/java/com/notenest/domain/MusicalKey.java))

- 24개 = 12 음높이 × 장·단조, 이명동음은 합친다(`C#m` = `Dbm` → `C_SHARP_MINOR`).
- 허용: `Am`, `am`, `A minor`, `a min`, `Amin`, `A`, `A major`, `Amaj`, `F#m`, `F♯m`, `Gbm`, `F sharp minor`, `B flat major`, enum 이름 `A_MINOR`
- 거부: `AM`·`F#M`(대문자 M 단독), `a`·`bb`(소문자 음이름 단독 — 독일식 단조 표기와 충돌), `라단조`·`가장조`(한글, P0 제외), `H`, `A-`, `Am7`, `A dorian`, `Key: Am`, `8A`(캄로트)
- 관계조·캄로트 동치, 하프/더블타임 BPM 동치는 다루지 않는다.

### 5.3 스키마·시드

- [scripts/migration/nb5-01-add-bpm-musical-key.sql](../../scripts/migration/nb5-01-add-bpm-musical-key.sql): `bpm INT NULL`, `musical_key VARCHAR(20) NULL`, CHECK 2개, 재실행 안전, 롤백 절 포함. 엔티티는 `columnDefinition = "varchar(20)"` 로 두었다. Hibernate 가 MariaDB 네이티브 ENUM 을 만들지 않게 해 마이그레이션과 맞춘다(ddl-auto 결과 `varchar(20)` 확인).
- 리허설 [raw/phase1-nb5-01-rehearsal.txt](raw/phase1-nb5-01-rehearsal.txt): 격리 스키마에서 추가 → 재실행 → 기존 행 보존 → 정상 3건 저장 → 위반 4건(39, 251, `Am`, `AM`) 전부 거부 → 롤백 후 컬럼 0. 통과했다.
- 시드 생성기: 평가 곡은 설계 속성을 적재하고, filler 는 별도 시드(20260927)로 BPM(15% null)·키(20% null)를 넣는다. filler 의 기존 필드는 Phase 0 과 같은지 70건을 대조해 확인했다(불일치 0).

## 6. 미해결 — 측정 스키마에서 CHECK 추가 실패 (환경 문제로 판단)

- 현상: `notenest_nb5.music` 에 nb5-01 STEP 3(CHECK 추가, 테이블 복사 ALTER)을 실행하면 `ERROR 1025 … errno: 194 "Tablespace is missing for a table"` 가 난다. 서버 로그는 `Cannot rename './notenest_nb5/music.ibd' … source file does not exist` 이다(파일은 존재한다).
- 영향: STEP 2(컬럼 추가, instant ALTER)는 성공한다. 실패한 ALTER 는 원자적으로 되돌려져 데이터 손실이 없다(10,000행 유지 확인). 결과적으로 측정 스키마에는 **CHECK 없이 컬럼만** 있다. 앱의 400 검증이 1차 방어이므로 기능과 측정에는 영향이 없다. 실패 확인 중 CHECK 동작을 보려고 실행한 UPDATE 2건은 제약이 없어 반영됐다. 그 뒤 코퍼스를 다시 적재해 값을 되돌렸다(범위 밖 BPM 0건 확인).
- 재현과 분리:

| 시도 | 결과 |
|---|---|
| 기존 `notenest_nb5.music`(Phase 0 에서 생성) | 실패 ×3 (FLUSH TABLES 후 포함) |
| 스키마 재생성 → 앱 ddl-auto 로 새 테이블 → 10,000곡 적재 → 적용 | 실패 ×2 |
| 격리 스키마의 작은 테이블(FK 없음 / 자식 FK / 부모로 참조됨) | 성공 |
| 같은 구조 복사본(`CREATE TABLE LIKE`, FK 없음) + 9,999행 | 성공 |
| 리허설 스크립트 | 성공 |

  마이그레이션 SQL 자체는 맞다. 실패는 이 컨테이너의 Windows 바인드 마운트(`N1\mariadb`)에서 FK 로 엮인 실제 `music` 테이블을 복사·rename 할 때 생기는 것으로 보인다. **정확한 원인은 분리하지 못했다.**
- 주의: 같은 컨테이너의 `notenest` DB 에 nb5-01 을 적용해도 STEP 3 이 같은 이유로 실패할 수 있다. `notenest` 에는 적용하지 않았다(범위 밖, 초기화 금지).

## 7. 착수 게이트 갱신

| 게이트 | 상태 |
|---|---|
| NB1 P0 완료 | 완료 |
| `%keyword%` OR 검색의 관련도·한글 분석 한계를 고정 코퍼스로 재현 | 완료(Phase 0) — qrels 로 수치화: T1 MRR@5 0.3889 |
| qrels 와 기대 Top-K 를 검색 구현 전에 확정 | **작성·자동 검증 완료, 소민님 검토 대기**(§2) |
| `bpm`·`musicalKey` 입력·정규화·필터 계약 확정 | 완료 |
| 대안별 동일 질의 결과와 EXPLAIN 또는 실행 근거 | LIKE 만 완료 → Phase 2 |

## 8. 다음 단계 (Phase 2 — 이번에는 시작하지 않음)

1. qrels v1 검토·확정(소민님)
2. MariaDB FULLTEXT 스파이크: 같은 25개 질의 → 같은 하네스로 nDCG@5·MRR@5. `innodb_ft_min_token_size=3`(2글자 한글 `봄비`·`이별`), ngram 파서 부재(MariaDB) 확인
3. Elasticsearch 8.11.1 + Nori 최소 스파이크(이 단계에서 처음 Docker 서비스를 추가한다)
4. 품질·성능·운영 복잡도 표 → ADR → 중단·보고
