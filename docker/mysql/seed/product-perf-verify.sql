-- product-perf-seed.sql 검증 쿼리
--
-- 실행: docker compose -f ./docker/infra-compose.yml exec -T mysql \
--         mysql -uapplication -papplication loopers --table < docker/mysql/seed/product-perf-verify.sql
--
-- 모든 행의 ok 가 'OK' 이고 마지막 mismatch 가 0 이면 seed 가 공식대로 들어간 것이다.

-- 1. products 전체: count 10000, SUM(amount) 472,685,000
SELECT
    'products total'         AS item,
    10000                    AS expected_count,
    COUNT(*)                 AS actual_count,
    472685000                AS expected_sum,
    SUM(amount)              AS actual_sum,
    IF(COUNT(*) = 10000 AND SUM(amount) = 472685000, 'OK', 'MISMATCH') AS ok
FROM products;

-- 2. brand_id = 7: count 100, SUM(amount) 4,647,200
SELECT
    'products brand 7'       AS item,
    100                      AS expected_count,
    COUNT(*)                 AS actual_count,
    4647200                  AS expected_sum,
    SUM(amount)              AS actual_sum,
    IF(COUNT(*) = 100 AND SUM(amount) = 4647200, 'OK', 'MISMATCH') AS ok
FROM products
WHERE brand_id = 7;

-- 3. likes 전체: count 229,870
SELECT
    'likes total'            AS item,
    229870                   AS expected_count,
    COUNT(*)                 AS actual_count,
    IF(COUNT(*) = 229870, 'OK', 'MISMATCH') AS ok
FROM likes;

-- 4. 좋아요 0개 상품 1000개, 500개 상품 10개
SELECT
    'products with like count 0 / 500' AS item,
    1000                     AS expected_zero,
    SUM(c.like_count = 0)    AS actual_zero,
    10                       AS expected_500,
    SUM(c.like_count = 500)  AS actual_500,
    IF(SUM(c.like_count = 0) = 1000 AND SUM(c.like_count = 500) = 10, 'OK', 'MISMATCH') AS ok
FROM (
    SELECT p.id, COUNT(l.id) AS like_count
    FROM products p
    LEFT JOIN likes l ON l.product_id = p.id
    GROUP BY p.id
) c;

-- 5. 상품별 좋아요 수가 공식과 다른 상품 수 (0건이어야 한다)
SELECT
    'products whose like count differs from formula' AS item,
    0                        AS expected_mismatch,
    COUNT(*)                 AS actual_mismatch,
    IF(COUNT(*) = 0, 'OK', 'MISMATCH') AS ok
FROM (
    SELECT
        p.id,
        COUNT(l.id) AS actual_count,
        CASE
            WHEN p.id % 10 = 0 THEN 0
            WHEN p.id % 1000 = 1 THEN 500
            ELSE (p.id * 13) % 50
        END AS expected_count
    FROM products p
    LEFT JOIN likes l ON l.product_id = p.id
    GROUP BY p.id
) c
WHERE c.actual_count <> c.expected_count;
