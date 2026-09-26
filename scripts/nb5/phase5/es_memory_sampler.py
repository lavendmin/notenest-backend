"""NB5 Phase 5 — Elasticsearch 컨테이너 메모리·JVM 힙 샘플러.

1초 남짓 간격으로 `docker stats`(컨테이너 메모리, cgroup 기준)와 `_nodes/stats/jvm`(힙 사용·최대)을 CSV 로 남긴다.
재색인·대조·부하 구간 표시는 --mark 로 같은 파일에 적는다. 멈추려면 stop 파일을 만든다.

  python scripts/nb5/phase5/es_memory_sampler.py run  --out docs/nb5/raw/phase5-es-memory.csv
  python scripts/nb5/phase5/es_memory_sampler.py mark --out docs/nb5/raw/phase5-es-memory.csv --label "k6 search warm1 start"
  python scripts/nb5/phase5/es_memory_sampler.py stop --out docs/nb5/raw/phase5-es-memory.csv
"""
import argparse
import json
import subprocess
import time
import urllib.request
from datetime import datetime
from pathlib import Path

CONTAINER = "notenest-es"


def to_mib(text: str) -> float:
    text = text.strip()
    units = {"KiB": 1 / 1024, "MiB": 1, "GiB": 1024, "kB": 1 / 1024, "MB": 1, "GB": 1024, "B": 1 / 1024 / 1024}
    for unit, factor in units.items():
        if text.endswith(unit):
            return float(text[: -len(unit)]) * factor
    return float("nan")


def sample(es_url: str):
    out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{json .}}", CONTAINER], capture_output=True)
    mem_mib = limit_mib = float("nan")
    cpu = ""
    if out.returncode == 0 and out.stdout.strip():
        stats = json.loads(out.stdout.decode("utf-8").splitlines()[0])
        used, limit = stats["MemUsage"].split("/")
        mem_mib, limit_mib, cpu = to_mib(used), to_mib(limit), stats["CPUPerc"]
    heap_used = heap_max = float("nan")
    try:
        with urllib.request.urlopen(f"{es_url}/_nodes/stats/jvm", timeout=2) as resp:
            node = next(iter(json.load(resp)["nodes"].values()))
            heap_used = node["jvm"]["mem"]["heap_used_in_bytes"] / 1024 / 1024
            heap_max = node["jvm"]["mem"]["heap_max_in_bytes"] / 1024 / 1024
    except Exception:  # noqa: BLE001 — ES 가 멈춘 구간도 기록은 계속한다
        pass
    return mem_mib, limit_mib, cpu, heap_used, heap_max


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["run", "mark", "stop"])
    ap.add_argument("--out", required=True)
    ap.add_argument("--label", default="")
    ap.add_argument("--es", default="http://localhost:9201")
    args = ap.parse_args()
    out = Path(args.out)
    stop = out.with_suffix(".stop")

    if args.command == "mark":
        with out.open("a", encoding="utf-8") as f:
            f.write(f"{datetime.now().isoformat(timespec='seconds')},MARK,{args.label},,,\n")
        return
    if args.command == "stop":
        stop.touch()
        return

    stop.unlink(missing_ok=True)
    if not out.exists():
        out.write_text("time,container_mem_mib,container_limit_mib,cpu,heap_used_mib,heap_max_mib\n", encoding="utf-8")
    while not stop.exists():
        mem, limit, cpu, heap_used, heap_max = sample(args.es)
        with out.open("a", encoding="utf-8") as f:
            f.write(f"{datetime.now().isoformat(timespec='seconds')},{mem:.1f},{limit:.1f},{cpu},{heap_used:.1f},{heap_max:.1f}\n")
        time.sleep(1)
    stop.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
