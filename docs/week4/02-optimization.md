# 4주차 02. 상품 목록 조회 개선 기록

측정값만 기록한다. 측정 조건·도구·순서는 `01-baseline.md` 2절과 같고, 단계별 원문은 `docs/week4/perf/<단계 폴더>/` 에 있다.

## step1: likes(product_id) 인덱스

### 변경

- 커밋 `43c07d5` feat: likes(product_id) 인덱스 추가 (step1)
- `Like` 엔티티 `@Table` 에 `@Index(name = "idx_likes_product_id", columnList = "product_id")` 1개 추가. 기존 `uk_likes_user_product(user_id, product_id)` 유지. 다른 코드 변경 없음.
- local·test 프로필은 `ddl-auto: create` 라 재기동·테스트 시 `create index idx_likes_product_id on likes (product_id)` 가 자동 실행된다. 다른 프로필은 별도 DDL 이 필요하다.
- 테스트: `./gradlew :apps:commerce-api:test --tests '*Like*' --tests '*Product*'` 109건 통과 (실패·오류·skip 0).

### 측정 환경 (`perf/step1/env.txt`)

2026-10-16 13:24 ~ 13:34 KST, HEAD `43c07d5`, 앱 옵션 없이 local 재기동, seed 후 verify 20개 OK. `SHOW INDEX FROM likes` 에 `idx_likes_product_id` (cardinality 9,222) 확인. 그 외 환경은 baseline 과 동일.

### 응답 일치

| 요청 | baseline 대비 응답 JSON | 캡처 SQL 문자열 |
|---|---|---|
| A (brandId=7, price_asc) | 바이트 단위 동일 | 3개 동일 |
| B (brandId=7, likes_desc) | 바이트 단위 동일 | 3개 동일 |
| C (필터 없음, price_asc) | 바이트 단위 동일 | 15개 동일 |

### SQL 수와 읽은 행 수 (1회 호출, performance_schema)

| 요청 | SQL | baseline SQL 수 | step1 SQL 수 | baseline ms / rows_examined | step1 ms / rows_examined |
|---|---|---|---|---|---|
| A | 상품 목록 | 1 | 1 | 1.983 / 10,020 | 2.536 / 10,020 |
| A | 좋아요 집계 (IN 20) | 1 | 1 | 13.428 / 223,037 | 0.290 / 437 |
| A | 브랜드 조회 | 1 | 1 | 0.084 / 1 | 0.126 / 1 |
| B | 상품 목록 (likes_desc 정렬 서브쿼리) | 1 | 1 | 1294.861 / 22,313,720 | 8.568 / 12,228 |
| B | 좋아요 집계 (IN 20) | 1 | 1 | 13.564 / 223,037 | 0.303 / 850 |
| B | 브랜드 조회 | 1 | 1 | 0.112 / 1 | 0.108 / 1 |
| C | 상품 목록 | 1 | 1 | 2.424 / 10,020 | 2.722 / 10,020 |
| C | 좋아요 집계 (IN 20) | 1 | 1 | 20.324 / 223,037 | 0.399 / 448 |
| C | 브랜드 조회 × 13 | 13 | 13 | 합 1.369 / 각 1 | 합 0.578 / 각 1 |

요청당 SQL 수는 A 3, B 3, C 15 로 변화 없음. 부수 문장(set_option, commit, Ping) 수도 동일.

### EXPLAIN 변화

| 요청 | SQL | 항목 | baseline | step1 |
|---|---|---|---|---|
| A | 상품 목록 | type / key / rows / Extra | ALL / NULL / 9955 / Using where; Using filesort | ALL / NULL / 9855 / 동일 |
| A | 좋아요 집계 | type / key / rows / Extra | index / uk_likes_user_product / 222504 / Using where; Using index; Using temporary | range / idx_likes_product_id / 439 / Using where; Using index |
| A | 브랜드 조회 | type / key | const / PRIMARY | const / PRIMARY |
| B | 상품 목록 외부 | type / key / rows / Extra | ALL / NULL / 9955 / Using where; Using filesort | ALL / NULL / 9855 / 동일 |
| B | 정렬 서브쿼리 (DEPENDENT SUBQUERY) | type / key / rows / Extra | index / uk_likes_user_product / 222504 / Using where; Using index | ref / idx_likes_product_id / 24 / Using index |
| B | 좋아요 집계 | type / key / rows / Extra | index / uk_likes_user_product / 222504 / Using where; Using index; Using temporary | range / idx_likes_product_id / 850 / Using where; Using index |

EXPLAIN ANALYZE 주요 노드 (actual rows / loops / actual time ms)

| 요청 | 노드 | baseline | step1 |
|---|---|---|---|
| A | 좋아요 집계: likes 접근 | Covering index scan 223037 / 1 / 0.507..14.9 | Covering index range scan 437 / 1 / 0.00733..0.0575 |
| A | 좋아요 집계: 집계 | Aggregate using temporary table 18 / 1 / 20.3 | Group aggregate 18 / 1 / 0.0154..0.0909 |
| A | 상품 목록: Sort amount, id DESC (limit 20) | 20 / 1 / 1.76 | 20 / 1 / 2.32 |
| B | 정렬 서브쿼리: likes 접근 | Covering index scan 223037 / 100 / 0.119..12.5 | Covering index lookup 22.1 / 100 / 0.0106..0.012 |
| B | 정렬 서브쿼리: Aggregate count | 1 / 100 / 17.1 | 1 / 100 / 0.013 |
| B | 상품 목록: Sort (select #2) DESC, id DESC (limit 20) | 20 / 1 / 1712 | 20 / 1 / 3.61 |
| B | 좋아요 집계: likes 접근 | Covering index scan 223037 / 1 / 0.135..12.3 | Covering index range scan 850 / 1 / 0.011..0.0785 |
| B | 좋아요 집계: 집계 | Aggregate using temporary table 20 / 1 / 17.5 | Group aggregate 20 / 1 / 0.0225..0.133 |

### A 응답 시간 (k6, 준비 50 + 측정 500 × 3회, 단위 ms)

VU 1명 회차별 (`perf/step1/k6-A-vu1-r{1,2,3}.*`)

| 회차 | avg | p50 | p90 | p95 | max | req/s | 실패 | 상품 조회 SQL 3종 | 그 외 SELECT |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 6.94 | 6.66 | 7.80 | 8.29 | 26.72 | 142.71 | 0 | 1500 | 0 |
| 2 | 6.65 | 6.27 | 7.22 | 8.00 | 17.45 | 148.75 | 0 | 1500 | 0 |
| 3 | 6.83 | 6.61 | 8.15 | 8.50 | 12.52 | 144.34 | 0 | 1500 | 0 |

회차 간 편차 (범위 ÷ 평균): avg 4.3%, p50 6.0%, p90 12.0%, p95 6.1%, max 75.1%, req/s 4.2%.

VU 5명 회차별 (`perf/step1/k6-A-vu5-r{1,2,3}.*`)

| 회차 | avg | p50 | p90 | p95 | max | req/s | 실패 | 상품 조회 SQL 3종 | 그 외 SELECT |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 8.56 | 8.15 | 9.89 | 10.71 | 20.25 | 577.50 | 0 | 1500 | 0 |
| 2 | 8.74 | 8.36 | 10.21 | 10.85 | 21.90 | 566.14 | 0 | 1500 | 0 |
| 3 | 8.25 | 7.99 | 9.39 | 10.07 | 12.09 | 600.80 | 0 | 1500 | 0 |

회차 간 편차 (범위 ÷ 평균): avg 5.8%, p50 4.5%, p90 8.3%, p95 7.4%, max 54.3%, req/s 6.0%.

baseline 대비 (3회차 평균)

| 동시 요청 | 지표 | baseline | step1 | 차이 | 비율 (step1 ÷ baseline) |
|---|---|---|---|---|---|
| VU 1 | avg | 22.06 | 6.81 | −15.25 | 0.31 |
| VU 1 | p50 | 22.01 | 6.51 | −15.50 | 0.30 |
| VU 1 | p95 | 23.31 | 8.26 | −15.05 | 0.35 |
| VU 1 | max | 31.36 | 18.90 | −12.46 | 0.60 |
| VU 1 | req/s | 44.97 | 145.27 | +100.30 | 3.23 |
| VU 5 | avg | 25.45 | 8.52 | −16.93 | 0.33 |
| VU 5 | p50 | 24.93 | 8.17 | −16.76 | 0.33 |
| VU 5 | p95 | 30.24 | 10.54 | −19.70 | 0.35 |
| VU 5 | max | 48.44 | 18.08 | −30.36 | 0.37 |
| VU 5 | req/s | 195.16 | 581.48 | +386.32 | 2.98 |
| 공통 | 요청당 SQL | 3.000 | 3.000 | 0 | 1.00 |

### B 응답 시간 (10회 순차 curl, 단위 초)

step1 10회: 0.0218, 0.0096, 0.0082, 0.0118, 0.0086, 0.0103, 0.0084, 0.0132, 0.0081, 0.0084 (모두 200). 10회 구간 상품 조회 SQL 30 (10/10/10), 그 외 SELECT 0.

| 지표 | baseline | step1 | 비율 |
|---|---|---|---|
| avg | 1.186 | 0.0108 | 0.009 |
| p50 | 1.175 | 0.0091 | 0.008 |
| min | 1.128 | 0.0081 | 0.007 |
| max | 1.310 | 0.0218 | 0.017 |

### 특이사항

- 1회 호출 소요 시간은 재기동 후 첫 요청이라 참고값이다 (A 0.208초, B 0.077초, C 0.039초).
- `events_statements_history_long` consumer 는 baseline 측정 이후 컨테이너를 재시작하지 않아 YES 로 유지된 상태였다.

## 측정하지 않은 범위

- 브랜드 필터 없는 likes_desc(전체 상품 대상 좋아요순)는 측정하지 않았다. B(브랜드 7, 상품 100개)는 인덱스로 개선돼도, 필터가 없으면 전체 상품의 좋아요를 세고 정렬해야 하므로 상품 수가 늘면 비용이 계속 커질 수 있다. 이 경우 like_count 같은 구조 변경이 필요한지는 확인하지 않았다.
