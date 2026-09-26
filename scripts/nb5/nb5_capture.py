"""NB5 질의셋을 현재 목록 API(GET /api/music/filter)로 실행하고 결과 순서를 기록한다.

비로그인으로 호출한다(목록은 공개 API). 응답의 musicUuid 를 코퍼스 id 로 되돌려 기록하며,
filler 곡은 F##### id 로 표시된다.

사용법 (레포 루트):
  python scripts/nb5/nb5_capture.py --label eval-only --total 57                                   # Phase 0
  python scripts/nb5/nb5_capture.py --label eval-only --total 57 --prefix phase1-like-results      # Phase 1
"""
import argparse
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import nb5_corpus  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
QUERIES = ROOT / "docs" / "nb5" / "eval" / "queries-v0.jsonl"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8096")
    ap.add_argument("--label", required=True)
    ap.add_argument("--total", type=int, required=True, help="적재한 전체 곡 수(filler id 역매핑용)")
    ap.add_argument("--size", type=int, default=10)
    ap.add_argument("--out", default=str(ROOT / "docs" / "nb5" / "raw"))
    ap.add_argument("--prefix", default="phase0-like-results", help="출력 파일 접두사 (Phase 1: phase1-like-results)")
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")  # Windows 기본 콘솔 인코딩(cp949) 대신 UTF-8 로 출력

    corpus = nb5_corpus.load_corpus()
    songs = corpus + nb5_corpus.filler_rows(args.total - len(corpus))
    by_uuid = {nb5_corpus.music_uuid(s["id"]): s for s in songs}
    queries = [json.loads(l) for l in QUERIES.open(encoding="utf-8") if l.strip()]

    results = []
    for qd in queries:
        params = {"searchTerm": qd["searchTerm"], "page": 0, "size": args.size, **qd["params"]}
        url = f"{args.base}/api/music/filter?" + urllib.parse.urlencode(params)
        with urllib.request.urlopen(url) as resp:
            body = json.load(resp)
        top = []
        for rank, item in enumerate(body["content"], 1):
            s = by_uuid.get(item["musicUuid"])
            top.append({
                "rank": rank,
                "id": s["id"] if s else "?",
                "title": item["title"],
                "seller": item["userNickName"],
                "status": s["status"] if s else None,
                "coverUrlPresent": bool(item.get("coverUrl")),
            })
        primary = qd.get("primary") or []
        ids = [t["id"] for t in top]
        results.append({
            "qid": qd["qid"], "type": qd["type"], "searchTerm": qd["searchTerm"], "params": qd["params"],
            "totalElements": body["totalElements"],
            "top": top,
            "primaryRanks": {p: (ids.index(p) + 1 if p in ids else None) for p in primary},
            "endedInTop": [t["id"] for t in top if t["status"] == 1],
        })

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / f"{args.prefix}-{args.label}.json").write_text(
        json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")

    lines = [f"# 현재 LIKE 검색 결과 순서 — {args.label} (곡 {args.total}건, size={args.size}, 비로그인, sortBy 기본 latest)", ""]
    for r in results:
        lines.append(f"## {r['qid']} {r['type']} `{r['searchTerm']}` {r['params'] or ''}")
        lines.append(f"- totalElements={r['totalElements']} primaryRanks={r['primaryRanks'] or '-'} endedInTop={r['endedInTop'] or '-'}")
        for t in r["top"]:
            lines.append(f"  {t['rank']:>2}. {t['id']:<6} {t['title']} / {t['seller']}")
        lines.append("")
    (out / f"{args.prefix}-{args.label}.md").write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
