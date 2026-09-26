#!/usr/bin/env bash
# NB5 Phase 5 — 같은 순서·조건으로 k6 측정(최종 ES 앱 / 기준선 LIKE 앱 모두 이 스크립트로).
#   bash scripts/nb5/phase5/run_load.sh <label> [base]      예) es, like
# 순서: 검색 cold(버림) → warm1~3 → 포화(대기 0, warm 뒤) → 무검색 cold(버림) → warm1~2
# 각 실행: 10 VU × 30s, 질의 25개 균등 순환(docs/nb5/eval/queries-v0.jsonl), 비로그인.
# 원시 출력: docs/nb5/raw/phase5-k6-<label>-<mode>-<run>.txt / .json(summary-export)
set -euo pipefail
LABEL="$1"
BASE="${2:-http://localhost:8096}"
OUT=docs/nb5/raw
SCRIPT=scripts/k6/nb5-search-baseline.js
MEM=docs/nb5/raw/phase5-es-memory.csv

mark() {
  if [ "$LABEL" = "es" ]; then python scripts/nb5/phase5/es_memory_sampler.py mark --out "$MEM" --label "$1"; fi
}

run() { # mode run sleep
  mark "k6 $LABEL $1 $2 start"
  k6 run --quiet -e MODE="$1" -e BASE="$BASE" -e SLEEP="$3" --summary-export "$OUT/phase5-k6-$LABEL-$1-$2.json" "$SCRIPT" \
    > "$OUT/phase5-k6-$LABEL-$1-$2.txt" 2>&1 || true
  mark "k6 $LABEL $1 $2 end"
  echo "$LABEL $1 $2 done"
}

for r in cold warm1 warm2 warm3; do run search "$r" 0.1; done
run search saturation 0
for r in cold warm1 warm2; do run nosearch "$r" 0.1; done
