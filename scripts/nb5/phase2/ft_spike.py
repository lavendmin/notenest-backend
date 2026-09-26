"""NB5 Phase 2 — MariaDB 10.11 InnoDB FULLTEXT 스파이크.

기존 notenest-db 컨테이너는 쓰지 않는다. 서버 설정(innodb_ft_min_token_size)을 바꿔 보려면 재시작이 필요하고,
그 컨테이너의 바인드 마운트에서는 테이블 재구성 ALTER 가 실패한 이력이 있기 때문이다(Phase 1 §6).
대신 이미지만 같은 일회용 컨테이너(볼륨 없음)를 띄워 측정하고 지운다.

  # 기본 설정(min_token_size=3)
  docker run -d --rm --name nb5-ft-probe -p 3312:3306 -e MARIADB_ROOT_PASSWORD=local-only mariadb:10.11 \
      --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
  # 한글 2글자 토큰 허용(min_token_size=1)
  docker run ... mariadb:10.11 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci --innodb-ft-min-token-size=1

  python scripts/nb5/phase2/ft_spike.py setup --total 57
  python scripts/nb5/phase2/ft_spike.py run   --total 57 --label ft-default-eval-only
  python scripts/nb5/phase2/ft_spike.py bench --total 10000 --label ft-default-n10000 --reps 20
  docker stop nb5-ft-probe

질의: 필드별 FULLTEXT 인덱스(제목·부제·태그·장르·설명, user.nickname)에 NATURAL LANGUAGE MODE 로 MATCH 하고
nb5_query 의 가중치로 합산한다. FULLTEXT 인덱스는 테이블을 넘지 못하므로 판매자 닉네임은 user 쪽 인덱스를 따로 MATCH 한다.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import nb5_query as nq  # noqa: E402
from nb5_query import nb5_corpus  # noqa: E402

SCHEMA = Path(__file__).resolve().parent / "schema-notenest_nb5.sql"
FT_INDEXES = [
    "ALTER TABLE music ADD FULLTEXT INDEX ft_title (title)",
    "ALTER TABLE music ADD FULLTEXT INDEX ft_subtitle (subtitle)",
    "ALTER TABLE music ADD FULLTEXT INDEX ft_hashtag (hashtag)",
    "ALTER TABLE music ADD FULLTEXT INDEX ft_genre (major_genre)",
    "ALTER TABLE music ADD FULLTEXT INDEX ft_details (details)",
    "ALTER TABLE user ADD FULLTEXT INDEX ft_nickname (nickname)",
]


def mariadb(container: str, sql: str, db: str = "notenest_nb5", table: bool = False) -> str:
    cmd = ["docker", "exec", "-i", container, "mariadb", "-uroot", "-plocal-only", "--default-character-set=utf8mb4"]
    cmd += ["-t"] if table else ["-N", "-B"]
    if db:
        cmd.append(db)
    res = subprocess.run(cmd, input=sql.encode("utf-8"), capture_output=True)
    if res.returncode != 0:
        raise RuntimeError(res.stderr.decode("utf-8", "replace"))
    return res.stdout.decode("utf-8")


def search_sql(query: dict, size: int) -> str:
    term = query["searchTerm"]
    q = nb5_corpus.q(term)
    cq = nb5_corpus.q(nq.compact(term))
    w = nq.WEIGHTS
    score = " + ".join([
        f"{w['title']} * MATCH(m.title) AGAINST({q})",
        f"{w['seller']} * MATCH(u.nickname) AGAINST({q})",
        f"{w['subtitle']} * MATCH(m.subtitle) AGAINST({q})",
        f"{w['hashtag']} * MATCH(m.hashtag) AGAINST({q})",
        f"{w['genre']} * MATCH(m.major_genre) AGAINST({q})",
        f"{w['details']} * MATCH(m.details) AGAINST({q})",
        f"{nq.EXACT_BOOST} * (LOWER(REPLACE(m.title, ' ', '')) = {cq})",
        f"{nq.EXACT_BOOST} * (LOWER(REPLACE(u.nickname, ' ', '')) = {cq})",
    ] + [f"{nq.GENRE_ALIAS_BOOST} * (m.major_genre = {nb5_corpus.q(c)})" for c in nq.genre_codes(term)])

    where = ["m.status = 0"]
    f = nq.structured_filters(query["params"])
    price = "COALESCE(m.current_highest_bid, m.starting_price)"
    if "genre" in f:
        where.append(f"m.major_genre = {nb5_corpus.q(f['genre'])}")
    if "minPrice" in f:
        where.append(f"{price} >= {f['minPrice']}")
    if "maxPrice" in f:
        where.append(f"{price} <= {f['maxPrice']}")
    if "bpmMin" in f:
        where.append(f"m.bpm >= {f['bpmMin']}")
    if "bpmMax" in f:
        where.append(f"m.bpm <= {f['bpmMax']}")
    if "key" in f:
        where.append(f"m.musical_key = {nb5_corpus.q(f['key'])}")

    return (f"SELECT m.music_uuid, ({score}) AS score, COUNT(*) OVER () AS hits "
            f"FROM music m JOIN user u ON u.user_uuid = m.user_uuid "
            f"WHERE {' AND '.join(where)} "
            f"HAVING score > 0 "
            f"ORDER BY score DESC, m.created_at DESC, m.music_uuid ASC LIMIT {size}")


def cmd_setup(args):
    mariadb(args.container, SCHEMA.read_text(encoding="utf-8"), db=None)
    sql = subprocess.run([sys.executable, str(Path(nb5_corpus.__file__)), "--total", str(args.total)],
                         capture_output=True, check=True).stdout.decode("utf-8")
    mariadb(args.container, sql, db=None)
    size_before = du(args.container)
    for ddl in FT_INDEXES:
        mariadb(args.container, ddl + ";")
    print(mariadb(args.container,
                  "SELECT @@version, @@innodb_ft_min_token_size AS ft_min_token, @@innodb_ft_enable_stopword AS stopword, "
                  "(SELECT COUNT(*) FROM music) AS music_rows;", table=True))
    print(f"datadir notenest_nb5: before FT {size_before} / after FT {du(args.container)}")


def du(container: str) -> str:
    out = subprocess.run(["docker", "exec", container, "du", "-sh", "/var/lib/mysql/notenest_nb5"], capture_output=True)
    return out.stdout.decode().split()[0] if out.returncode == 0 else "?"


def cmd_run(args):
    by_uuid = {nb5_corpus.music_uuid(s["id"]): s for s in nb5_corpus.build_songs(args.total)}
    runs = []
    for query in nq.load_queries():
        rows = [l.split("\t") for l in mariadb(args.container, search_sql(query, nq.SIZE) + ";").splitlines() if l]
        top = []
        for rank, (uuid, score, hits) in enumerate(rows, 1):
            s = by_uuid[uuid]
            top.append({"rank": rank, "id": s["id"], "score": float(score), "title": s["title"], "seller": s["seller"]})
        runs.append({**{k: query[k] for k in ("qid", "type", "searchTerm", "params")},
                     "totalHits": int(rows[0][2]) if rows else 0, "top": top})
    print(nq.write_run("phase2-results", args.label, runs))


def cmd_bench(args):
    """서버 측 실행 시간(SHOW PROFILES). 질의마다 reps 회 반복하고, 비교용으로 현재 LIKE SQL 도 같은 세션 조건에서 잰다."""
    queries = nq.load_queries()
    results = {"fulltext": {}, "like": {}}
    for engine in results:
        for query in queries:
            sql = search_sql(query, nq.SIZE) if engine == "fulltext" else like_sql(query, nq.SIZE)
            script = "SET profiling = 1; SET profiling_history_size = 100;\n" + (sql + ";\n") * args.reps + "SHOW PROFILES;\n"
            out = mariadb(args.container, script)
            durations = [float(l.split("\t")[1]) * 1000 for l in out.splitlines() if re.match(r"^\d+\t[\d.]+\t", l)]
            results[engine][query["qid"]] = durations[-args.reps:]
    lines = [f"# FULLTEXT vs LIKE 서버 측 실행 시간 — {args.label}", "",
             f"곡 {args.total}건, 질의 25개 × {args.reps}회, SHOW PROFILES Duration(ms), 첫 회 포함", "",
             "| 엔진 | p50 | p90 | p99 | 평균 |", "|---|---|---|---|---|"]
    for engine, per_q in results.items():
        allv = sorted(v for vs in per_q.values() for v in vs)
        lines.append(f"| {engine} | {pct(allv, 50):.3f} | {pct(allv, 90):.3f} | {pct(allv, 99):.3f} | {sum(allv) / len(allv):.3f} |")
    lines += ["", "| qid | fulltext p50 | like p50 |", "|---|---|---|"]
    for qd in queries:
        lines.append(f"| {qd['qid']} | {pct(sorted(results['fulltext'][qd['qid']]), 50):.3f} | "
                     f"{pct(sorted(results['like'][qd['qid']]), 50):.3f} |")
    out = nq.RAW / f"phase2-bench-{args.label}.md"
    out.write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines[:9]))
    print(out)


def like_sql(query: dict, size: int) -> str:
    """현재 앱의 LIKE 검색 SQL(Phase 0 캡처와 같은 모양) + Phase 1 의 BPM·키 필터. 본문 쿼리만 잰다."""
    kw = "%" + query["searchTerm"].strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
    like = nb5_corpus.q(kw)
    where = ["m.status = 0", "(" + " OR ".join(f"{c} LIKE {like} ESCAPE '!'" for c in
                                            ("m.title", "m.subtitle", "m.major_genre", "m.hashtag", "u.nickname")) + ")"]
    f = nq.structured_filters(query["params"])
    price = "COALESCE(m.current_highest_bid, m.starting_price)"
    if "genre" in f:
        where.append(f"m.major_genre = {nb5_corpus.q(f['genre'])}")
    if "minPrice" in f:
        where.append(f"{price} >= {f['minPrice']}")
    if "maxPrice" in f:
        where.append(f"{price} <= {f['maxPrice']}")
    if "bpmMin" in f:
        where.append(f"m.bpm >= {f['bpmMin']}")
    if "bpmMax" in f:
        where.append(f"m.bpm <= {f['bpmMax']}")
    if "key" in f:
        where.append(f"m.musical_key = {nb5_corpus.q(f['key'])}")
    return (f"SELECT m.music_uuid, m.title FROM music m LEFT JOIN user u ON u.user_uuid = m.user_uuid "
            f"WHERE {' AND '.join(where)} ORDER BY m.created_at DESC LIMIT 0, {size}")


def pct(sorted_vals, p):
    if not sorted_vals:
        return 0.0
    k = max(0, min(len(sorted_vals) - 1, round(p / 100 * len(sorted_vals) + 0.5) - 1))
    return sorted_vals[k]


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["setup", "run", "bench"])
    ap.add_argument("--container", default="nb5-ft-probe")
    ap.add_argument("--total", type=int, required=True)
    ap.add_argument("--label", default="ft")
    ap.add_argument("--reps", type=int, default=20)
    args = ap.parse_args()
    {"setup": cmd_setup, "run": cmd_run, "bench": cmd_bench}[args.command](args)


if __name__ == "__main__":
    main()
