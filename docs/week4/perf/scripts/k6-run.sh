#!/usr/bin/env bash
# A 응답 시간 측정. 프로젝트 루트에서 실행한다.
# 사용법: bash docs/week4/perf/scripts/k6-run.sh <VUS> <라벨> <단계 폴더>
#   예) bash docs/week4/perf/scripts/k6-run.sh 1 vu1 baseline  → 5단계 (동시 1명), 결과 perf/baseline/k6-A-vu1-r{1,2,3}.*
#       bash docs/week4/perf/scripts/k6-run.sh 5 vu5 baseline  → 6단계 (동시 5명)
#       bash docs/week4/perf/scripts/k6-run.sh 1 vu1 step1     → 개선 후 1단계, 결과 perf/step1/k6-A-vu1-r{1,2,3}.*
# 결과: <단계 폴더>/k6-A-<라벨>-r{1,2,3}.json (k6 요약), .txt (한 줄 요약), -sql.txt (SQL 개수), k6-A-<라벨>-run.log (실행 로그)
# 단계 폴더가 없으면 만들고, 위 결과 파일 중 하나라도 이미 있으면 덮어쓰지 않고 k6 실행 전에 멈춘다.
# 회차마다: 준비 50회(결과 버림) → SQL 카운터 초기화 → 측정 500회 → SQL 개수 캡처. 3회 반복.
# 요청당 SQL 수 = 상품 조회 SQL 3종 합계(digest 기준) ÷ 500. 커넥션 초기화 등은 app_other_select 로 따로 센다.
set -euo pipefail

VUS="$1"
LABEL="$2"
STAGE="$3"
SCRIPTS=docs/week4/perf/scripts
OUTDIR="docs/week4/perf/$STAGE"
SCRIPT="$SCRIPTS/k6-products.js"
OUTBASE="$OUTDIR/k6-A-$LABEL"
LOG="$OUTBASE-run.log"
MYSQL=(docker compose -f ./docker/infra-compose.yml exec -T mysql mysql -uroot -proot loopers --table)

mkdir -p "$OUTDIR"
for f in "$LOG" "$OUTBASE"-r{1,2,3}.json "$OUTBASE"-r{1,2,3}.txt "$OUTBASE"-r{1,2,3}-sql.txt; do
  if [ -e "$f" ]; then
    echo "STOP: 결과 파일이 이미 있습니다: $f (덮어쓰지 않음)" >&2
    exit 1
  fi
done

run_rounds() {
  for r in 1 2 3; do
    echo "=== round $r (vus=$VUS) warmup 50 ==="
    k6 run --quiet -e VUS="$VUS" -e ITERS=50 "$SCRIPT"

    echo "=== round $r reset counters ==="
    "${MYSQL[@]}" < "$SCRIPTS/ps-reset.sql" 2>&1 | grep -v "Using a password" || true

    echo "=== round $r measure 500 ==="
    k6 run --quiet -e VUS="$VUS" -e ITERS=500 -e OUT="$OUTBASE-r$r.json" "$SCRIPT" \
      | tee "$OUTBASE-r$r.txt"

    echo "=== round $r sql counters (ps-round-capture.sql) ==="
    "${MYSQL[@]}" < "$SCRIPTS/ps-round-capture.sql" 2>&1 | grep -v "Using a password" \
      | tee "$OUTBASE-r$r-sql.txt"
  done
}

run_rounds 2>&1 | tee "$LOG"
