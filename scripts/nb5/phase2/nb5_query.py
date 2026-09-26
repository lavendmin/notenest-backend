"""NB5 Phase 2 스파이크 공통 — 질의 해석과 관련도 설정.

이 파일의 값은 **스파이크를 실행하기 전에 고정했다**(qrels 결과를 보고 조정하지 않는다).
FULLTEXT 와 Elasticsearch 가 같은 설정을 쓰므로 두 후보의 차이는 엔진(토큰화·점수)에서만 나온다.

- 필드 가중치: 제목·판매자 3 > 부제 2 > 태그 1.5 > 장르·설명 1 (결정 1 — 설명은 제목·부제보다 낮게)
- 정확 일치: 정규화(소문자·공백 제거)한 제목 또는 판매자 닉네임이 검색어와 같으면 +100.
  두 경우의 boost 는 같다(결정 5 — 정확 제목과 정확 판매자가 충돌해도 한쪽을 우선하지 않는다).
- 한글 장르명: 검색어의 어절이 별칭이면 해당 장르 코드 곡에 +2 (결정 2 — 명시적 매핑).
  LIKE 기준선에는 적용하지 않는다(기준선 고정).
- 구조화 조건(진행 중·장르·가격·BPM·키)은 점수와 무관한 필터다.
- 정렬: 점수 desc → created_at desc → music_uuid asc (안정적 tie-break).
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import nb5_corpus  # noqa: E402

ROOT = Path(__file__).resolve().parents[3]
QUERIES = ROOT / "docs" / "nb5" / "eval" / "queries-v0.jsonl"
RAW = ROOT / "docs" / "nb5" / "raw"

WEIGHTS = {"title": 3.0, "seller": 3.0, "subtitle": 2.0, "hashtag": 1.5, "genre": 1.0, "details": 1.0}
EXACT_BOOST = 100.0
GENRE_ALIAS_BOOST = 2.0
GENRE_ALIASES = {"발라드": "balad", "힙합": "hiphop", "트로트": "trot", "팝": "pop", "케이팝": "pop"}
SIZE = 10


def compact(text: str) -> str:
    return "".join(text.lower().split())


def genre_codes(term: str):
    return sorted({GENRE_ALIASES[t] for t in term.split() if t in GENRE_ALIASES})


def structured_filters(params: dict) -> dict:
    """질의셋 params → 구조화 필터 값. musicalKey 는 코퍼스와 같은 짧은 표기(Am 등)만 쓴다."""
    f = {}
    if "majorGenre" in params:
        f["genre"] = params["majorGenre"]
    for k in ("minPrice", "maxPrice", "bpmMin", "bpmMax"):
        if k in params:
            f[k] = int(params[k])
    if "musicalKey" in params:
        f["key"] = nb5_corpus.key_code(params["musicalKey"])
    return f


def load_queries():
    return [json.loads(l) for l in QUERIES.open(encoding="utf-8") if l.strip()]


def write_run(prefix: str, label: str, runs: list):
    """nb5_capture.py 와 같은 형식(qid, top[].id)으로 저장 — ./gradlew nb5Eval 로 채점한다."""
    RAW.mkdir(parents=True, exist_ok=True)
    path = RAW / f"{prefix}-{label}.json"
    path.write_text(json.dumps(runs, ensure_ascii=False, indent=1), encoding="utf-8")
    lines = [f"# {prefix} — {label}", ""]
    for r in runs:
        lines.append(f"## {r['qid']} {r['type']} `{r['searchTerm']}` {r['params'] or ''}")
        lines.append(f"- hits={r['totalHits']}")
        for t in r["top"]:
            lines.append(f"  {t['rank']:>2}. {t['id']:<6} {t['score']:>9.4f}  {t['title']} / {t['seller']}")
        lines.append("")
    (RAW / f"{prefix}-{label}.md").write_text("\n".join(lines), encoding="utf-8")
    return path
