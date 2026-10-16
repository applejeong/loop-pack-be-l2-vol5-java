#!/usr/bin/env bash
# A 응답 시간 측정. 프로젝트 루트에서 실행한다.
# 사용법: bash docs/week4/perf/scripts/k6-run.sh <VUS> <라벨> <단계 폴더>
#   예) bash docs/week4/perf/scripts/k6-run.sh 1 vu1 baseline  → 5단계 (동시 1명)
#       bash docs/week4/perf/scripts/k6-run.sh 5 vu5 baseline  → 6단계 (동시 5명)
#       bash docs/week4/perf/scripts/k6-run.sh 1 vu1 step1     → 개선 후 1단계
# 결과 (측정 1번당 2개):
#   <단계 폴더>/k6-A-<라벨>-summary.tsv : 회차별 지표 + SQL 개수, mean / min / max / range_pct 행
#   <단계 폴더>/k6-A-<라벨>-run.log     : 전체 실행 로그 (k6 한 줄 요약, SQL 캡처 출력 포함)
# 단계 폴더가 없으면 만들고, 위 두 파일 중 하나라도 이미 있으면 덮어쓰지 않고 k6 실행 전에 멈춘다.
# 회차마다: 준비 50회(결과 버림) → SQL 카운터 초기화 → 측정 500회 → SQL 개수 캡처. 3회 반복.
# 회차별 중간 결과는 임시 폴더에만 두고 summary.tsv 를 만든 뒤 지운다.
# 요청당 SQL 수 = 상품 조회 SQL 3종 합계(digest 기준) ÷ 요청 수. 커넥션 초기화 등은 other_select 로 따로 센다.
# summary.tsv 의 mean 행은 회차별 값의 산술 평균이다 (p50·p90·p95·max 도 회차 값의 평균이며 전체 요청을 합친 백분위가 아니다).
set -euo pipefail

VUS="$1"
LABEL="$2"
STAGE="$3"
SCRIPTS=docs/week4/perf/scripts
OUTDIR="docs/week4/perf/$STAGE"
SCRIPT="$SCRIPTS/k6-products.js"
SUMMARY="$OUTDIR/k6-A-$LABEL-summary.tsv"
LOG="$OUTDIR/k6-A-$LABEL-run.log"
MYSQL=(docker compose -f ./docker/infra-compose.yml exec -T mysql mysql -uroot -proot loopers --table)

mkdir -p "$OUTDIR"
for f in "$SUMMARY" "$LOG"; do
  if [ -e "$f" ]; then
    echo "STOP: 결과 파일이 이미 있습니다: $f (덮어쓰지 않음)" >&2
    exit 1
  fi
done

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

run_rounds() {
  for r in 1 2 3; do
    echo "=== round $r (vus=$VUS) warmup 50 ==="
    k6 run --quiet -e VUS="$VUS" -e ITERS=50 "$SCRIPT"

    echo "=== round $r reset counters ==="
    "${MYSQL[@]}" < "$SCRIPTS/ps-reset.sql" 2>&1 | grep -v "Using a password" || true

    echo "=== round $r measure 500 ==="
    k6 run --quiet -e VUS="$VUS" -e ITERS=500 "$SCRIPT" | tee "$TMP/k6-r$r.txt"

    echo "=== round $r sql counters (ps-round-capture.sql) ==="
    "${MYSQL[@]}" < "$SCRIPTS/ps-round-capture.sql" 2>&1 | grep -v "Using a password" \
      | tee "$TMP/sql-r$r.txt"
  done
}

# k6 한 줄 요약(vus=… avg=… checks_fail=…)과 ps-round-capture 의 6숫자 요약 행에서 회차 1줄을 만든다.
round_row() {
  local r="$1" k6 sql
  k6=$(grep -E '^vus=' "$TMP/k6-r$r.txt" | tail -1)
  sql=$(grep -E '^\| +[0-9]+ \| +[0-9]+ \| +[0-9]+ \| +[0-9]+ \| +[0-9]+ \| +-?[0-9]+ \|' "$TMP/sql-r$r.txt" | head -1)
  awk -v r="$r" -v k6="$k6" -v sql="$sql" 'BEGIN {
    n = split(k6, a, " "); for (i = 1; i <= n; i++) { split(a[i], kv, "="); v[kv[1]] = kv[2] }
    split(sql, s, "|"); for (i = 2; i <= 7; i++) gsub(/ /, "", s[i])
    printf "%s\t%s\t%s\t%s\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%.3f\n",
      r, v["vus"], v["iters"], v["reqs"], v["avg"], v["p50"], v["p90"], v["p95"], v["max"], v["rps"],
      v["http_failed"], v["checks_fail"], s[3], s[4], s[5], s[6], s[7], s[6] / v["reqs"]
  }'
}

build_summary() {
  {
    printf "round\tvus\titers\treqs\tavg_ms\tp50_ms\tp90_ms\tp95_ms\tmax_ms\treq_s\thttp_fail\tcheck_fail\tsql_list\tsql_like_agg\tsql_brand\tsql_product_total\tother_select\tsql_per_req\n"
    for r in 1 2 3; do round_row "$r"; done
  } > "$TMP/rows.tsv"

  # 5~18열에 대해 mean / min / max / range_pct 행을 덧붙인다. 11~17열(건수)은 평균이 정수면 정수로 찍는다.
  awk -F'\t' 'BEGIN { OFS = "\t" }
    NR == 1 { print; next }
    { print; n++; for (c = 5; c <= 18; c++) { sum[c] += $c; if (n == 1 || $c < mn[c]) mn[c] = $c; if (n == 1 || $c > mx[c]) mx[c] = $c } ; vus = $2; iters = $3; reqs = $4 }
    END {
      for (c = 5; c <= 18; c++) mean[c] = sum[c] / n
      printf "mean\t%s\t%s\t%s", vus, iters, reqs
      for (c = 5; c <= 18; c++) printf "\t%s", fmt(c, mean[c]); printf "\n"
      printf "min\t%s\t%s\t%s", vus, iters, reqs
      for (c = 5; c <= 18; c++) printf "\t%s", fmt(c, mn[c]); printf "\n"
      printf "max\t%s\t%s\t%s", vus, iters, reqs
      for (c = 5; c <= 18; c++) printf "\t%s", fmt(c, mx[c]); printf "\n"
      printf "range_pct\t-\t-\t-"
      for (c = 5; c <= 18; c++) printf "\t%.1f", (mean[c] > 0 ? (mx[c] - mn[c]) / mean[c] * 100 : 0); printf "\n"
    }
    function fmt(c, x) {
      if (c == 18) return sprintf("%.3f", x)
      if (c >= 11 && c <= 17) return (x == int(x)) ? sprintf("%d", x) : sprintf("%.2f", x)
      return sprintf("%.2f", x)
    }' "$TMP/rows.tsv" > "$SUMMARY"
}

run_rounds 2>&1 | tee "$LOG"
build_summary
echo "summary: $SUMMARY"
cat "$SUMMARY"
