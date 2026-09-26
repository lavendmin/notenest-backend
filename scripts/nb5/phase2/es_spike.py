"""NB5 Phase 2 — Elasticsearch 8.11.1 + Nori 최소 스파이크.

앱(Spring) 연동 없이 REST 로 색인·검색해 토큰과 순위를 확인한다. 본 구현(검색 포트·어댑터)은 Phase 3 의 일이다.

  docker compose up -d --build notenest-es          # 9201
  python scripts/nb5/phase2/es_spike.py setup   --total 57
  python scripts/nb5/phase2/es_spike.py analyze
  python scripts/nb5/phase2/es_spike.py run     --total 57 --label es-nori-eval-only
  python scripts/nb5/phase2/es_spike.py setup   --total 10000
  python scripts/nb5/phase2/es_spike.py bench   --total 10000 --label es-nori-n10000 --reps 20

색인 문서는 MariaDB 에 적재하는 것과 같은 곡(nb5_corpus.build_songs)이다 — 같은 등록 시각·속성.
설정(가중치·정확 일치·장르 별칭)은 nb5_query 에 고정돼 있고 FULLTEXT 스파이크와 같다.
"""
import argparse
import json
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import nb5_query as nq  # noqa: E402
from nb5_query import nb5_corpus  # noqa: E402

INDEX = "nb5-music-spike"

SETTINGS = {
    "number_of_shards": 1,
    "number_of_replicas": 0,
    "analysis": {
        "tokenizer": {
            # mixed: 복합명사를 원형과 분해형으로 함께 낸다(여름밤 → 여름밤, 여름, 밤)
            "nori_mixed": {"type": "nori_tokenizer", "decompound_mode": "mixed", "discard_punctuation": True},
        },
        "analyzer": {
            # nori_part_of_speech 기본 stoptags 가 조사·어미 등을 제거한다(봄비가 → 봄비)
            "ko": {"type": "custom", "tokenizer": "nori_mixed", "filter": ["nori_part_of_speech", "lowercase"]},
        },
        "char_filter": {"strip_ws": {"type": "pattern_replace", "pattern": "\\s+", "replacement": ""}},
        "normalizer": {"compact": {"type": "custom", "char_filter": ["strip_ws"], "filter": ["lowercase"]}},
    },
}

MAPPINGS = {
    "dynamic": "strict",
    "properties": {
        "music_id": {"type": "keyword"},
        "created_at": {"type": "date"},
        "title": {"type": "text", "analyzer": "ko", "fields": {"compact": {"type": "keyword", "normalizer": "compact"}}},
        "subtitle": {"type": "text", "analyzer": "ko"},
        "details": {"type": "text", "analyzer": "ko"},
        "hashtag": {"type": "text", "analyzer": "standard"},
        "genre": {"type": "keyword"},
        "seller": {"type": "text", "analyzer": "ko", "fields": {"compact": {"type": "keyword", "normalizer": "compact"}}},
        "status": {"type": "integer"},
        "price": {"type": "long"},  # 현재가(최고 입찰가), 없으면 시작가 — 목록 가격 필터 기준
        "bpm": {"type": "integer"},
        "musical_key": {"type": "keyword"},
        "cover_key": {"type": "keyword", "index": False},  # URL 은 색인하지 않는다(응답 직전 발급)
    },
}

ANALYZE_SAMPLES = ["봄비", "봄비가 내리면", "봄비처럼 스며든 기억", "여름밤", "여름 밤의 꿈", "한여름밤",
                   "Blue Hour", "bluehour", "너의 이름을 부르면", "잔잔한 피아노 발라드", "피아노가 잔잔하게 흐르는 곡",
                   "신나는 여름 댄스곡", "이별 후의 새벽", "새벽공방", "새벽공방스튜디오", "새벽 공방의 소리",
                   "윤슬", "BPM 90 / Key: Am", "120%의 에너지", "Lovely Day", "Glove"]


def http(method: str, url: str, body=None, ndjson: bool = False):
    data = None
    headers = {}
    if body is not None:
        data = body.encode("utf-8") if isinstance(body, str) else json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/x-ndjson" if ndjson else "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if method == "DELETE" and e.code == 404:
            return None
        raise RuntimeError(f"{method} {url} → {e.code} {e.read().decode('utf-8', 'replace')}")


def document(s: dict) -> dict:
    return {
        "music_id": nb5_corpus.music_uuid(s["id"]),
        "created_at": s["created"].strftime("%Y-%m-%dT%H:%M:%S"),
        "title": s["title"], "subtitle": s["subtitle"], "details": s["details"] or None, "hashtag": s["hashtag"],
        "genre": s["genre"], "seller": s["seller"], "status": s["status"],
        "price": s["bid"] if s["bid"] is not None else s["start"],
        "bpm": s["bpm"], "musical_key": s["key_code"], "cover_key": nb5_corpus.COVER_KEY,
    }


def search_body(query: dict, size: int) -> dict:
    term = query["searchTerm"]
    w = nq.WEIGHTS
    should = [
        {"multi_match": {"query": term, "type": "best_fields", "tie_breaker": 0.3,
                         "fields": [f"title^{w['title']}", f"seller^{w['seller']}", f"subtitle^{w['subtitle']}",
                                    f"hashtag^{w['hashtag']}", f"genre^{w['genre']}", f"details^{w['details']}"]}},
        {"constant_score": {"filter": {"term": {"title.compact": nq.compact(term)}}, "boost": nq.EXACT_BOOST}},
        {"constant_score": {"filter": {"term": {"seller.compact": nq.compact(term)}}, "boost": nq.EXACT_BOOST}},
    ] + [{"constant_score": {"filter": {"term": {"genre": c}}, "boost": nq.GENRE_ALIAS_BOOST}}
         for c in nq.genre_codes(term)]

    filters = [{"term": {"status": 0}}]
    f = nq.structured_filters(query["params"])
    if "genre" in f:
        filters.append({"term": {"genre": f["genre"]}})
    price = {k: f[p] for k, p in (("gte", "minPrice"), ("lte", "maxPrice")) if p in f}
    if price:
        filters.append({"range": {"price": price}})
    bpm = {k: f[p] for k, p in (("gte", "bpmMin"), ("lte", "bpmMax")) if p in f}
    if bpm:
        filters.append({"range": {"bpm": bpm}})
    if "key" in f:
        filters.append({"term": {"musical_key": f["key"]}})

    return {
        "size": size, "track_total_hits": True,
        "query": {"bool": {"filter": filters, "should": should, "minimum_should_match": 1}},
        "sort": [{"_score": "desc"}, {"created_at": "desc"}, {"music_id": "asc"}],
        "_source": ["music_id"],
    }


def cmd_setup(args):
    http("DELETE", f"{args.url}/{INDEX}")
    http("PUT", f"{args.url}/{INDEX}", {"settings": SETTINGS, "mappings": MAPPINGS})
    songs = nb5_corpus.build_songs(args.total)
    for i in range(0, len(songs), 1000):
        lines = []
        for s in songs[i:i + 1000]:
            doc = document(s)
            lines.append(json.dumps({"index": {"_id": doc["music_id"]}}))
            lines.append(json.dumps(doc, ensure_ascii=False))
        res = http("POST", f"{args.url}/{INDEX}/_bulk", "\n".join(lines) + "\n", ndjson=True)
        if res["errors"]:
            raise RuntimeError(json.dumps(res["items"][:3], ensure_ascii=False))
    http("POST", f"{args.url}/{INDEX}/_refresh")
    info = http("GET", f"{args.url}")
    stats = http("GET", f"{args.url}/_cat/indices/{INDEX}?format=json&bytes=kb")
    print(f"es {info['version']['number']} / docs {stats[0]['docs.count']} / store {stats[0]['store.size']}kb")


def cmd_analyze(args):
    lines = ["# Nori 분석 결과 (analyzer ko: nori_tokenizer mixed + nori_part_of_speech + lowercase)", "",
             "| 입력 | 토큰 |", "|---|---|"]
    for text in ANALYZE_SAMPLES:
        res = http("POST", f"{args.url}/{INDEX}/_analyze", {"analyzer": "ko", "text": text})
        lines.append(f"| {text} | {' · '.join(t['token'] for t in res['tokens'])} |")
    for text in ["Blue Hour", "새벽 공방"]:
        res = http("POST", f"{args.url}/{INDEX}/_analyze", {"normalizer": "compact", "text": text})
        lines.append(f"| {text} (normalizer compact) | {' · '.join(t['token'] for t in res['tokens'])} |")
    plugins = http("GET", f"{args.url}/_cat/plugins?format=json")
    lines += ["", "plugins: " + ", ".join(f"{p['component']} {p['version']}" for p in plugins)]
    out = nq.RAW / "phase2-es-nori-tokens.md"
    out.write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines))


def cmd_run(args):
    by_uuid = {nb5_corpus.music_uuid(s["id"]): s for s in nb5_corpus.build_songs(args.total)}
    runs = []
    for query in nq.load_queries():
        res = http("POST", f"{args.url}/{INDEX}/_search", search_body(query, nq.SIZE))
        top = []
        for rank, hit in enumerate(res["hits"]["hits"], 1):
            s = by_uuid[hit["_source"]["music_id"]]
            top.append({"rank": rank, "id": s["id"], "score": hit["sort"][0], "title": s["title"], "seller": s["seller"]})
        runs.append({**{k: query[k] for k in ("qid", "type", "searchTerm", "params")},
                     "totalHits": res["hits"]["total"]["value"], "top": top})
    print(nq.write_run("phase2-results", args.label, runs))


def cmd_bench(args):
    """서버 측 took(ms)와 클라이언트 왕복(ms). 질의마다 reps 회, 첫 회 포함."""
    took, wall = [], []
    per_q = {}
    for query in nq.load_queries():
        body = search_body(query, nq.SIZE)
        ts = []
        for _ in range(args.reps):
            t0 = time.perf_counter()
            res = http("POST", f"{args.url}/{INDEX}/_search?request_cache=false", body)
            wall.append((time.perf_counter() - t0) * 1000)
            took.append(res["took"])
            ts.append(res["took"])
        per_q[query["qid"]] = sorted(ts)
    took.sort()
    wall.sort()
    stats = http("GET", f"{args.url}/_cat/indices/{INDEX}?format=json&bytes=kb")
    lines = [f"# Elasticsearch 검색 시간 — {args.label}", "",
             f"문서 {stats[0]['docs.count']}건, store {stats[0]['store.size']}kb, 질의 25개 × {args.reps}회, request_cache=false", "",
             "| 지표 | p50 | p90 | p99 | 평균 |", "|---|---|---|---|---|",
             f"| took(서버, ms 정수) | {pct(took, 50)} | {pct(took, 90)} | {pct(took, 99)} | {sum(took) / len(took):.3f} |",
             f"| 왕복(클라이언트, ms) | {pct(wall, 50):.3f} | {pct(wall, 90):.3f} | {pct(wall, 99):.3f} | {sum(wall) / len(wall):.3f} |",
             "", "| qid | took p50 |", "|---|---|"]
    lines += [f"| {qid} | {pct(v, 50)} |" for qid, v in per_q.items()]
    out = nq.RAW / f"phase2-bench-{args.label}.md"
    out.write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines[:8]))
    print(out)


def pct(sorted_vals, p):
    k = max(0, min(len(sorted_vals) - 1, round(p / 100 * len(sorted_vals) + 0.5) - 1))
    return sorted_vals[k]


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["setup", "analyze", "run", "bench"])
    ap.add_argument("--url", default="http://localhost:9201")
    ap.add_argument("--total", type=int, default=57)
    ap.add_argument("--label", default="es")
    ap.add_argument("--reps", type=int, default=20)
    args = ap.parse_args()
    {"setup": cmd_setup, "analyze": cmd_analyze, "run": cmd_run, "bench": cmd_bench}[args.command](args)


if __name__ == "__main__":
    main()
