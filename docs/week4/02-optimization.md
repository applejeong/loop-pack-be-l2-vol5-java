# 4주차 02. 상품 목록 조회 개선 기록

측정값만 기록한다. 측정 조건·도구·순서는 `01-baseline.md` 2절과 같고, 단계별 원문은 `docs/week4/perf/<단계 폴더>/` 에 있다 (k6 결과는 `k6-A-<라벨>-summary.tsv` 와 `k6-A-<라벨>-run.log`). 요약 수치는 summary.tsv(스크립트 printf 반올림) 기준이며, mean 행의 백분위(p50·p90·p95)와 max 는 회차별 값의 평균이고 전체 요청을 합친 백분위가 아니다.

측정 조건 보충
- step2 VU5·B·C 는 스크립트 커밋 `e9b9f19` 이후 측정, 앱 코드는 `0f4a094` (스크립트 변경은 결과 파일 형식만).
- 요청당 SQL 수는 계정(application) 기준 SELECT 합계 ÷ 요청 수로 적는다. digest 기준 3종 합계는 보조 값이다.
- A·B·C 1회 호출의 ms 는 표본 1개라 "참고값(1회)" 이다. 단계 간 비교는 rows_examined·실행 계획·SQL 수 중심으로 적는다.
- C EXPLAIN 은 step2 에서만 기록했고 전후 비교가 아니다.

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

VU 1명 회차별 (`perf/step1/k6-A-vu1-summary.tsv`)

| 회차 | avg | p50 | p90 | p95 | max | req/s | 실패 | 상품 조회 SQL 3종 | 그 외 SELECT |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 6.94 | 6.66 | 7.80 | 8.29 | 26.72 | 142.71 | 0 | 1500 | 0 |
| 2 | 6.65 | 6.27 | 7.22 | 8.00 | 17.45 | 148.75 | 0 | 1500 | 0 |
| 3 | 6.83 | 6.61 | 8.15 | 8.50 | 12.52 | 144.34 | 0 | 1500 | 0 |

회차 간 편차 (범위 ÷ 평균): avg 4.3%, p50 6.0%, p90 12.0%, p95 6.1%, max 75.1%, req/s 4.2%.

VU 5명 회차별 (`perf/step1/k6-A-vu5-summary.tsv`)

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

## step2: products(brand_id, deleted_at, amount, id DESC) 인덱스 (step1 누적)

### 변경

- 커밋 `0f4a094` feat: products(brand_id, deleted_at, amount, id DESC) 인덱스 추가 (step2)
- `Product` 엔티티 `@Table` 에 `@Index(name = "idx_products_brand_deleted_amount_id", columnList = "brand_id, deleted_at, amount, id DESC")` 1개 추가. step1 의 `idx_likes_product_id` 와 다른 코드는 그대로.
- Hibernate 6.6.11 은 `columnList` 의 ASC/DESC 를 파싱해 DDL 에 넣는다 (`IndexBinder` → `Index.selectableOrderMap` → `StandardIndexExporter`). 생성된 DDL: `create index idx_products_brand_deleted_amount_id on products (brand_id, deleted_at, amount, id desc)`. `SHOW INDEX FROM products` 에서 id 열 Collation `D` 확인.
- local·test 프로필은 `ddl-auto: create` 라 재기동·테스트 시 자동 생성된다. 다른 프로필은 별도 DDL 이 필요하다.
- 테스트: `./gradlew :apps:commerce-api:test --tests '*Like*' --tests '*Product*'` BUILD SUCCESSFUL, 109건 통과.

| 테스트 클래스 | 실행 | 실패 | 오류 | skip |
|---|---|---|---|---|
| LikeTest | 7 | 0 | 0 | 0 |
| LikeServiceTest | 7 | 0 | 0 | 0 |
| LikeFacadeTest | 9 | 0 | 0 | 0 |
| LikeV1ApiE2ETest | 10 | 0 | 0 | 0 |
| ProductTest | 12 | 0 | 0 | 0 |
| ProductSortTypeTest | 3 | 0 | 0 | 0 |
| ProductServiceTest | 22 | 0 | 0 | 0 |
| ProductRepositoryTest | 12 | 0 | 0 | 0 |
| ProductFacadeTest | 11 | 0 | 0 | 0 |
| ProductV1ApiE2ETest | 9 | 0 | 0 | 0 |
| AdminCrudV1ApiE2ETest (상품 중첩 클래스) | 7 | 0 | 0 | 0 |
| 합계 | 109 | 0 | 0 | 0 |

### 측정 환경 (`perf/step2/env.txt`)

2026-10-16 13:51 ~ 15:23 KST, 앱 코드 HEAD `0f4a094`. 앱은 13:50:40 KST 기동(PID 83905) 후 재기동 없음, MySQL 컨테이너도 재기동 없음. 옵션 없이 local 재기동 → seed → verify 20개 OK. 인덱스: products PRIMARY + `idx_products_brand_deleted_amount_id (brand_id, deleted_at, amount, id DESC)` / likes PRIMARY, `uk_likes_user_product`, `idx_likes_product_id`. 그 외 환경은 baseline 과 동일.

### 응답 일치

| 요청 | baseline 대비 응답 JSON | 캡처 SQL 문자열 | 계정 기준 SELECT 수 |
|---|---|---|---|
| A (brandId=7, price_asc) | 바이트 단위 동일 | 3개 동일 | 3 (baseline·step1 과 동일) |
| B (brandId=7, likes_desc) | 바이트 단위 동일 | 3개 동일 | 3 |
| C (필터 없음, price_asc) | 바이트 단위 동일 | 15개 동일 | 15 |

### SQL 수와 읽은 행 수 (1회 호출, performance_schema)

ms 는 참고값(1회).

| 요청 | SQL | SQL 수 (baseline / step1 / step2) | rows_examined baseline | step1 | step2 | ms 참고값(1회) baseline / step1 / step2 |
|---|---|---|---|---|---|---|
| A | 상품 목록 | 1 / 1 / 1 | 10,020 | 10,020 | 20 | 1.983 / 2.536 / 0.905 |
| A | 좋아요 집계 (IN 20) | 1 / 1 / 1 | 223,037 | 437 | 437 | 13.428 / 0.290 / 0.739 |
| A | 브랜드 조회 | 1 / 1 / 1 | 1 | 1 | 1 | 0.084 / 0.126 / 0.191 |
| B | 상품 목록 (likes_desc 정렬 서브쿼리) | 1 / 1 / 1 | 22,313,720 | 12,228 | 2,328 | 1294.861 / 8.568 / 2.443 |
| B | 좋아요 집계 (IN 20) | 1 / 1 / 1 | 223,037 | 850 | 850 | 13.564 / 0.303 / 0.260 |
| B | 브랜드 조회 | 1 / 1 / 1 | 1 | 1 | 1 | 0.112 / 0.108 / 0.143 |
| C | 상품 목록 (필터 없음) | 1 / 1 / 1 | 10,020 | 10,020 | 10,020 | 2.424 / 2.722 / 3.512 |
| C | 좋아요 집계 (IN 20) | 1 / 1 / 1 | 223,037 | 448 | 448 | 20.324 / 0.399 / 0.428 |
| C | 브랜드 조회 × 13 | 13 / 13 / 13 | 각 1 | 각 1 | 각 1 | 합 1.369 / 0.578 / 0.594 |

요청당 SQL 수는 A 3, B 3, C 15 로 세 단계 모두 같다. `no_index_used` 는 A·B 상품 목록 쿼리가 baseline·step1 의 1 에서 step2 에 0 이 됐고, C 상품 목록은 1 그대로다.

### EXPLAIN 변화 (type / key / rows / Extra)

| 요청 | SQL | baseline | step1 | step2 |
|---|---|---|---|---|
| A | 상품 목록 | ALL / NULL / 9955 / Using where; Using filesort | ALL / NULL / 9855 / 동일 | ref / idx_products_brand_deleted_amount_id / 100 / Using index condition |
| A | 좋아요 집계 | index / uk_likes_user_product / 222504 / Using where; Using index; Using temporary | range / idx_likes_product_id / 439 / Using where; Using index | range / idx_likes_product_id / 439 / 동일 |
| A | 브랜드 조회 | const / PRIMARY / 1 | const / PRIMARY / 1 | const / PRIMARY / 1 |
| B | 상품 목록 외부 | ALL / NULL / 9955 / Using where; Using filesort | ALL / NULL / 9855 / 동일 | ref / idx_products_brand_deleted_amount_id / 100 / Using index condition; Using filesort |
| B | 정렬 서브쿼리 (DEPENDENT SUBQUERY) | index / uk_likes_user_product / 222504 / Using where; Using index | ref / idx_likes_product_id / 24 / Using index | ref / idx_likes_product_id / 23 / Using index |
| B | 좋아요 집계 | index / uk_likes_user_product / 222504 / Using where; Using index; Using temporary | range / idx_likes_product_id / 850 / Using where; Using index | range / idx_likes_product_id / 850 / 동일 |
| B | 브랜드 조회 | const / PRIMARY / 1 | const / PRIMARY / 1 | const / PRIMARY / 1 |

C EXPLAIN (step2 에서만 기록, 전후 비교 아님. `perf/step2/C-explain.txt`, 브랜드 조회는 13개 중 id=2 하나만 대표로 포함)

| SQL | type / key / rows / Extra | EXPLAIN ANALYZE 주요 노드 (actual rows / loops / ms) |
|---|---|---|
| 상품 목록 (필터 없음) | ALL / NULL / 9700 / Using where; Using filesort | Table scan 10000 / 1 / 0.0621..1.63 → Filter(deleted_at is null) 10000 → Sort amount, id DESC (limit 20) 20 / 1 / 2.69 |
| 좋아요 집계 | range / idx_likes_product_id / 449 / Using where; Using index | Covering index range scan 448 / 1 / 0.0157..0.0792 → Group aggregate 19 / 1 / 0.0239..0.12 |
| 브랜드 조회 (id=2) | const / PRIMARY / 1 | Rows fetched before execution 1 / 1 / 0..0.000042 |

EXPLAIN ANALYZE 주요 노드 (actual rows / loops / actual time ms), step1 → step2

| 요청 | 노드 | step1 | step2 |
|---|---|---|---|
| A | 상품 목록: products 접근 | Table scan 10000 / 1 / 0.0317..1.94 → Filter 100 | Index lookup on idx_products_brand_deleted_amount_id (brand_id=7, deleted_at=NULL) 20 / 1 / 0.026..0.0385 |
| A | 상품 목록: Sort amount, id DESC (limit 20) | 20 / 1 / 2.32 | 노드 없음 (Limit 20 / 1 / 0.0305..0.0438) |
| A | 좋아요 집계: likes 접근 | Covering index range scan 437 / 1 / 0.00733..0.0575 | Covering index range scan 437 / 1 / 0.00767..0.0567 |
| A | 좋아요 집계: 집계 | Group aggregate 18 / 1 / 0.0154..0.0909 | Group aggregate 18 / 1 / 0.0161..0.0908 |
| B | 상품 목록: products 접근 | Table scan 10000 / 1 / 0.204..1.86 → Filter 100 | Index lookup on idx_products_brand_deleted_amount_id (brand_id=7, deleted_at=NULL) 100 / 1 / 0.0236..0.108 |
| B | 상품 목록: Sort (select #2) DESC, id DESC (limit 20) | 20 / 1 / 3.61 | 20 / 1 / 0.525..0.526 |
| B | 정렬 서브쿼리: likes 접근 | Covering index lookup 22.1 / 100 / 0.0106..0.012 | Covering index lookup 22.1 / 100 / 0.00207..0.0032 |
| B | 정렬 서브쿼리: Aggregate count | 1 / 100 / 0.013 | 1 / 100 / 0.00379..0.00381 |
| B | 좋아요 집계: likes 접근 | Covering index range scan 850 / 1 / 0.011..0.0785 | Covering index range scan 850 / 1 / 0.00717..0.0963 |
| B | 좋아요 집계: 집계 | Group aggregate 20 / 1 / 0.0225..0.133 | Group aggregate 20 / 1 / 0.0179..0.158 |

### A 응답 시간 (k6, 준비 50 + 측정 500 × 3회, 단위 ms)

VU 1명 회차별 (`perf/step2/k6-A-vu1-summary.tsv`)

| 회차 | avg | p50 | p90 | p95 | max | req/s | 실패 | 계정 기준 SELECT | digest 3종 합계 | 그 외 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 5.14 | 4.48 | 6.89 | 8.82 | 31.91 | 192.28 | 0 | 1500 | 1500 | 0 |
| 2 | 4.32 | 4.18 | 4.76 | 5.04 | 6.87 | 229.15 | 0 | 1500 | 1500 | 0 |
| 3 | 4.21 | 4.09 | 4.56 | 4.76 | 6.95 | 234.91 | 0 | 1500 | 1500 | 0 |

회차 간 편차 (범위 ÷ 평균): avg 20.4%, p50 9.2%, p90 43.1%, p95 65.4%, max 164.3%, req/s 19.5%.

VU 5명 회차별 (`perf/step2/k6-A-vu5-summary.tsv`)

| 회차 | avg | p50 | p90 | p95 | max | req/s | 실패 | 계정 기준 SELECT | digest 3종 합계 | 그 외 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 7.41 | 6.72 | 9.69 | 11.19 | 16.89 | 667.24 | 0 | 1500 | 1500 | 0 |
| 2 | 6.77 | 6.50 | 8.22 | 8.68 | 13.49 | 727.35 | 0 | 1500 | 1490 | 10 |
| 3 | 7.02 | 6.52 | 8.11 | 9.07 | 31.97 | 704.93 | 0 | 1500 | 1494 | 6 |

회차 간 편차 (범위 ÷ 평균): avg 9.1%, p50 3.3%, p90 18.2%, p95 26.0%, max 88.9%, req/s 8.6%. 요청당 SQL 수는 계정 기준 1,500 ÷ 500 = 3.000 (summary.tsv 의 digest 기준 sql_per_req 는 2.980 / 2.988, 특이사항 참고).

baseline · step1 · step2 (summary.tsv mean 행)

| 동시 요청 | 지표 | baseline | step1 | step2 | step2 ÷ baseline | step2 ÷ step1 |
|---|---|---|---|---|---|---|
| VU 1 | avg | 22.06 | 6.81 | 4.56 | 0.21 | 0.67 |
| VU 1 | p50 | 22.01 | 6.51 | 4.25 | 0.19 | 0.65 |
| VU 1 | p95 | 23.31 | 8.26 | 6.21 | 0.27 | 0.75 |
| VU 1 | max | 31.36 | 18.90 | 15.24 | 0.49 | 0.81 |
| VU 1 | req/s | 44.97 | 145.27 | 218.78 | 4.87 | 1.51 |
| VU 5 | avg | 25.45 | 8.52 | 7.07 | 0.28 | 0.83 |
| VU 5 | p50 | 24.93 | 8.17 | 6.58 | 0.26 | 0.81 |
| VU 5 | p95 | 30.24 | 10.54 | 9.65 | 0.32 | 0.92 |
| VU 5 | max | 48.44 | 18.08 | 20.78 | 0.43 | 1.15 |
| VU 5 | req/s | 195.16 | 581.48 | 699.84 | 3.59 | 1.20 |
| 공통 | 요청당 SQL (계정 기준) | 3.000 | 3.000 | 3.000 | 1.00 | 1.00 |

### B 응답 시간 (10회 순차 curl, 단위 초)

step2 10회: 0.0092, 0.0085, 0.0074, 0.0078, 0.0077, 0.0071, 0.0074, 0.0074, 0.0069, 0.0068 (모두 200). 10회 구간 계정 기준 SELECT 30, digest 3종 30 (10/10/10), 그 외 0.

| 지표 | baseline | step1 | step2 |
|---|---|---|---|
| avg | 1.186 | 0.0108 | 0.0076 |
| p50 | 1.175 | 0.0091 | 0.0074 |
| min | 1.128 | 0.0081 | 0.0068 |
| max | 1.310 | 0.0218 | 0.0092 |

### 특이사항

- 1회 호출 소요 시간은 재기동 후 첫 요청이거나 표본 1개라 참고값이다 (A 0.287초, B 0.075초, C 0.045초).
- VU5 2·3회차에서 digest 요약 표가 10·6건 누락. 계정 기준 SELECT 는 1,500 이고, 3회차는 문장 이력으로 1,500건 확인.
- `events_statements_history_long` consumer 는 baseline 측정 이후 컨테이너를 재시작하지 않아 YES 로 유지된 상태였다.

## 측정하지 않은 범위

- 브랜드 필터 없는 likes_desc(전체 상품 대상 좋아요순)는 측정하지 않았다. B(브랜드 7, 상품 100개)는 인덱스로 개선돼도, 필터가 없으면 전체 상품의 좋아요를 세고 정렬해야 하므로 상품 수가 늘면 비용이 계속 커질 수 있다. 이 경우 like_count 같은 구조 변경이 필요한지는 확인하지 않았다.
- 상품은 논리 삭제이고 복구 기능을 쓰지 않아 삭제 상품이 누적되므로 deleted_at 을 인덱스에 포함했다. 현재 seed 에는 삭제 상품이 없어 deleted_at 포함 효과는 측정하지 않았다.
- 인덱스가 brand_id 로 시작하므로 브랜드 필터가 없는 C(가격순 전체)는 이 인덱스로 정렬을 처리하지 못한다.
