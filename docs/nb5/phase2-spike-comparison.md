# NB5 Phase 2 — 대안 스파이크 비교 (LIKE · FULLTEXT · Elasticsearch+Nori)

작업일: 2026-09-26 / qrels v1 승인·고정 `0fa7fb4` / 스파이크 설정 사전 고정 `03546be` / 결정 제안: [ADR-001](adr-001-search-engine.md)

## 0. 요약

- **qrels v1 고정:** 소민님이 검증용 가설 정답표로 승인했다(검토 범위: 0~3등급 기준, T3·T4 대표 모호 사례, Q21 `#dance` 힙합 1등급 포함). 내용 해시 테스트(`QrelsIntegrityTest.qrelsV1IsFrozen`)가 변경을 막는다. 앞으로 등급을 바꿔야 하면 사유를 적은 v2 로 분리한다.
- **품질(평가 코퍼스 57곡):** 전체 nDCG@5 는 현재 LIKE 0.5084 → LIKE + 규칙 0.8359 → FULLTEXT(min 1) 0.8472 → **ES + Nori 0.8860**. T1 MRR@5 는 ES 와 LIKE + 규칙이 1.0000 이다.
- **귀속:** 개선의 대부분은 엔진과 무관한 **질의 규칙**(정확 일치 우선, 한글 장르 별칭, 설명 포함)에서 나왔다. ES 의 추가 이득(+0.05)은 한국어 형태소가 필요한 질의에 몰려 있고, 규칙만 쓴 대안 대비 0패다.
- **제안:** ES 8.11.1 + Nori 를 P0 로 채택하고, 규칙을 ES 질의에 옮긴다(ADR-001, 승인 대기).
- **미해결 유지:** 측정 스키마의 CHECK 제약 추가 실패(Phase 1 §6)는 그대로 미해결이다. DB 제약 적용 완료로 기록하지 않았고, main 반영 대상도 아니다.

## 1. 공정성 장치

| 장치 | 내용 |
|---|---|
| 정답표 고정 | qrels v1 해시 테스트. 스파이크 결과로 등급을 바꾸지 않았다 |
| 설정 사전 고정 | 필드 가중치(제목·판매자 3, 부제 2, 태그 1.5, 장르·설명 1), 정확 일치 +100(제목·판매자 동일, 결정 5), 한글 장르 별칭 +2 를 [nb5_query.py](../../scripts/nb5/phase2/nb5_query.py) 에 두고 **실행 전에 커밋**했다(`03546be`). 이후 값을 바꾸지 않았다 |
| 같은 문서·같은 필터 | 두 엔진 모두 `nb5_corpus.build_songs` 로 같은 곡·등록 시각·BPM·키를 적재했다. 진행 중·장르·가격·BPM·키는 점수와 무관한 필터다 |
| 사후 대조군 명시 | "LIKE + 규칙"은 FULLTEXT 기본 결과를 본 뒤 추가했다. 가중치는 사전 고정값 그대로이고, 개선분 중 규칙 몫을 분리하기 위한 것이다 |
| 버그 기록 | 대조군 첫 실행은 스크립트 패치 누락으로 FULLTEXT 질의를 다시 돌렸다(결과가 FULLTEXT 와 동일해 발견). 수정 후 다시 실행했고, 첫 결과는 덮어썼다 |

## 2. 후보별 구성

| 후보 | 실행 환경 | 질의 |
|---|---|---|
| 현재 LIKE (Phase 1) | 앱 API (`notenest_nb5`) | 기존 QueryDSL `%kw%` OR, 최신순 |
| LIKE + 규칙 | 일회용 MariaDB 10.11 컨테이너 | 필드별 LIKE 적중 × 가중치 합 + 정확 일치 + 장르 별칭, 설명 포함 |
| FULLTEXT 기본 | 일회용 MariaDB 10.11 (min_token_size=3) | 필드별 FT 인덱스 6개, NATURAL LANGUAGE MODE MATCH × 가중치 합 + 같은 규칙 |
| FULLTEXT min 1 | 일회용 MariaDB 10.11 (`--innodb-ft-min-token-size=1`) | 위와 같음 |
| ES + Nori | `notenest-es`(compose, 8.11.1 + analysis-nori 8.11.1, 힙 512MB) | `multi_match` best_fields(tie 0.3) + 정확 일치 constant_score + 장르 별칭, `bool.filter` 로 구조화 조건, 정렬 `_score → created_at → music_id` |

FULLTEXT 를 기존 `notenest-db` 에서 하지 않은 이유가 둘 있다. 서버 설정(min_token_size)은 재시작해야 바뀌고, 그 컨테이너의 바인드 마운트에서는 테이블 재구성 ALTER 가 실패한 이력이 있다. 일회용 컨테이너는 볼륨 없이 띄웠고 측정 뒤 제거했다.

## 3. 품질

원시: [비교표](raw/phase2-comparison-eval-only.md) · 후보별 채점 [LIKE](raw/phase1-like-eval-only-quality.md) · [FT 기본](raw/phase2-ft-default-eval-only-quality.md) · [LIKE+규칙](raw/phase2-like-rules-eval-only-quality.md) · [FT min 1](raw/phase2-ft-min1-eval-only-quality.md) · [ES](raw/phase2-es-nori-eval-only-quality.md) · 순위 원문 `raw/phase2-results-*.md`

| 후보 | 전체 | T1 MRR@5 | T1 | T2 | T3 | T4 |
|---|---|---|---|---|---|---|
| 현재 LIKE | 0.5084 | 0.3889 | 0.5472 | 0.3726 | 0.5079 | 0.6254 |
| FULLTEXT 기본(min 3) | 0.7059 | 0.8889 | 0.7510 | 0.4594 | 0.6957 | 0.9894 |
| LIKE + 규칙 | 0.8359 | 1.0000 | 0.8837 | 0.7153 | 0.7753 | 1.0000 |
| FULLTEXT min 1 | 0.8472 | 0.9444 | 0.8681 | 0.7419 | 0.8228 | 0.9947 |
| **ES + Nori** | **0.8860** | **1.0000** | **0.9357** | **0.7456** | **0.8757** | **1.0000** |

- FULLTEXT 기본은 2글자 한글 토큰이 색인되지 않는다. 그래서 `이별`(Q22)·`비트`(Q10)·`너의 이름`(Q08) 가 0건이다. `봄비`(Q01)가 1위인 것은 FULLTEXT 가 아니라 정확 일치 규칙 덕분이다.
- MariaDB 에는 ngram 파서가 없다: `ERROR 1128 Function 'ngram' is not defined` ([raw](raw/phase2-ft-ngram-parser.txt)).
- **10,000곡 T1:** 정확 제목의 정답 집합은 filler 와 무관하게 정해지므로 MRR 이 유효하다. 현재 LIKE 0.3611 / LIKE + 규칙 1.0000 / FULLTEXT min 1 0.9444 / ES 1.0000. 다른 유형은 filler 를 판정하지 않았으므로 10,000곡 품질로 쓰지 않는다(judged@5 0.84~0.91).
- 모든 엔진에서 T2 텍스트 조건(Q12 `BPM 90`, Q14 `120`, Q15 `Am`)은 0.64 이하다. 이는 엔진이 아니라 질의 해석의 한계이고, 구조화 필터로 푸는 것이 계약이다.

### Nori 토큰 ([raw](raw/phase2-es-nori-tokens.md))

| 입력 | 토큰 | 효과 |
|---|---|---|
| 봄비가 내리면 | 봄비 · 봄 · 비 · 내리 | 조사 제거 → `봄비` 와 일치 |
| 여름밤 / 여름 밤의 꿈 / 한여름밤 | 여름밤·여름·밤 / 여름·밤·꿈 / 여름밤·여름·밤 | 복합어 분해로 띄어쓰기 변형이 서로 맞는다 |
| 피아노가 잔잔하게 흐르는 곡 | 피아노 · 잔잔 · 흐르 · 곡 | 어순·활용이 달라도 `잔잔한 피아노` 와 일치 |
| 새벽공방스튜디오 | 새벽 · 공방 · 스튜디오 | `새벽공방` 과 부분 일치. 정확 판매자는 compact 키워드로 따로 가린다 |
| bluehour / Blue Hour(compact) | bluehour / bluehour | 공백 없는 입력은 형태소로는 못 맞추고, 정규화 키워드로 정확 일치 처리 |
| 너의 이름을 부르면 | 너 · 이름 · 부르 | 대명사 `너`·동사 어간이 남는다 |

## 4. 성능 (엔진 측 시간, 10,000곡, 질의 25개 × 20회, 첫 회 포함)

| 후보 | p50 | p90 | p99 | 측정 방식 | 원시 |
|---|---|---|---|---|---|
| 현재 LIKE SQL | 20.2 ms | 30.0 ms | 36.6 ms | `SHOW PROFILES` (일회용 컨테이너) | [raw](raw/phase2-bench-ft-min1-n10000.md) |
| LIKE + 규칙 SQL | 35.1 ms | 46.6 ms | 58.9 ms | 같음 | 같음 |
| FULLTEXT min 1 SQL | 25.4 ms | 35.3 ms | 45.2 ms | 같음 | 같음 |
| ES `took` | 3 ms | 5 ms | 11 ms | 서버 took(정수 ms), request_cache=false | [raw](raw/phase2-bench-es-nori-n10000.md) |
| ES 왕복 | 10.7 ms | 14.1 ms | 28.7 ms | 파이썬 urllib 클라이언트 | 같음 |

- 측정 방식이 다르므로 크기 차이만 참고한다. API p50/p90·처리량·오류율 비교는 Phase 5 에서 같은 k6 조건으로 한다.
- FULLTEXT 는 점수 계산을 위해 6개 MATCH 를 SELECT 에 두었다. 그래서 인덱스로 후보를 먼저 거르지 않고 행마다 계산하며, LIKE 보다 빠르지 않았다. 인덱스 기반 후보 선별로 바꾸면 빨라질 수 있다. 다만 기각 사유는 성능이 아니라 한국어 처리와 운영 부담이다.
- 자원: ES 컨테이너 메모리 1017 MiB / 상한 1 GiB, 힙 231 / 512 MB, OOMKilled=false, 재시작 0, 이미지 748 MB, 10,000곡 색인 2.9 MB ([raw](raw/phase2-es-resources.txt)). FULLTEXT 인덱스 6개는 57곡 스키마 기준 데이터 디렉터리를 0.8 MB → 3.9 MB 로 키웠다.

## 5. 재현 (레포 루트)

```bash
# FULLTEXT (일회용 컨테이너, min 3 또는 min 1)
docker run -d --rm --name nb5-ft-probe -p 3312:3306 -e MARIADB_ROOT_PASSWORD=local-only mariadb:10.11 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci --innodb-ft-min-token-size=1
python scripts/nb5/phase2/ft_spike.py setup --total 57
python scripts/nb5/phase2/ft_spike.py run --total 57 --label ft-min1-eval-only
python scripts/nb5/phase2/ft_spike.py run --total 57 --label like-rules-eval-only --mode like-rules
python scripts/nb5/phase2/ft_spike.py setup --total 10000 && python scripts/nb5/phase2/ft_spike.py bench --total 10000 --label ft-min1-n10000
docker stop nb5-ft-probe
# Elasticsearch
docker compose up -d --build notenest-es
python scripts/nb5/phase2/es_spike.py setup --total 57 && python scripts/nb5/phase2/es_spike.py analyze
python scripts/nb5/phase2/es_spike.py run --total 57 --label es-nori-eval-only
python scripts/nb5/phase2/es_spike.py setup --total 10000 && python scripts/nb5/phase2/es_spike.py bench --total 10000 --label es-nori-n10000
docker compose stop notenest-es
# 채점
./gradlew nb5Eval -Prun=docs/nb5/raw/phase2-results-es-nori-eval-only.json -Plabel=phase2-es-nori-eval-only
```

## 6. 착수 게이트 (전부 충족 — ADR 승인 대기)

| 게이트 | 상태 |
|---|---|
| NB1 P0 완료 | 완료 |
| `%keyword%` OR 검색의 관련도·한글 분석 한계 재현 | 완료(Phase 0·1) |
| qrels·기대 Top-K 를 구현 전에 확정 | 완료 — v1 승인·고정 |
| bpm·musicalKey 입력·정규화·필터 계약 | 완료(Phase 1). DB CHECK 제약 적용은 **미해결** |
| 대안별 동일 질의 결과와 실행 근거 | 완료(이 문서) |

## 7. 다음 (Phase 3 — 승인 후 시작)

Spring Data Elasticsearch 의존성(Boot 3.2 관리 버전, `dependencyInsight` 로 클라이언트 버전 확인), 검색 포트·어댑터, 스파이크 매핑·질의의 코드화를 한다. `searchTerm` 경로만 전환하고 기존 DTO·페이지·좋아요·커버 URL 계약 회귀 테스트를 통과시키며, 실제 ES 통합 테스트 태스크를 분리한다. Phase 3 에서 정할 것은 ES 메모리 상한(1 → 1.5 GiB 검토)과 ES 장애 시 동작(오류 응답 또는 LIKE + 규칙 폴백)이다.
