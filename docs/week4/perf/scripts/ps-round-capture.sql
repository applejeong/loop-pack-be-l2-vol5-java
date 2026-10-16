-- k6 측정 구간(500회) 뒤에 실행하는 SQL 개수 캡처 (root 로 실행)
-- 요청당 SQL 수 = product_list / like_count / brand_find 합계 ÷ 요청 수.
-- app_other_select 는 application 계정의 SELECT 중 상품 조회 3종이 아닌 것(커넥션 초기화 등)이다.

-- 1. 계정별 문장 종류 카운터 (application 만)
SELECT USER, EVENT_NAME, COUNT_STAR
  FROM performance_schema.events_statements_summary_by_user_by_event_name
 WHERE USER = 'application' AND COUNT_STAR > 0
 ORDER BY EVENT_NAME;

-- 2. 요약: 상품 조회 SQL 3종 합계와 그 외
--    digest 표는 계정 구분이 없으므로, 3종은 digest 로 세고 "그 외"는 application SELECT 총수에서 3종을 뺀 값으로 구한다.
SELECT u.app_select,
       d.product_list,
       d.like_count,
       d.brand_find,
       d.product_list + d.like_count + d.brand_find AS app_product_query_total,
       u.app_select - (d.product_list + d.like_count + d.brand_find) AS app_other_select
  FROM (SELECT COALESCE(SUM(COUNT_STAR), 0) AS app_select
          FROM performance_schema.events_statements_summary_by_user_by_event_name
         WHERE USER = 'application' AND EVENT_NAME = 'statement/sql/select') u,
       (SELECT COALESCE(SUM(CASE WHEN DIGEST_TEXT LIKE 'SELECT `p1_0` . `id` %FROM `products` `p1_0`%' THEN COUNT_STAR END), 0) AS product_list,
               COALESCE(SUM(CASE WHEN DIGEST_TEXT LIKE 'SELECT `l1_0` . `product_id` %FROM `likes` `l1_0`%' THEN COUNT_STAR END), 0) AS like_count,
               COALESCE(SUM(CASE WHEN DIGEST_TEXT LIKE 'SELECT `b1_0` . `id` %FROM `brands` `b1_0`%' THEN COUNT_STAR END), 0) AS brand_find
          FROM performance_schema.events_statements_summary_by_digest) d;

-- 3. digest 별 상세 (측정용 클라이언트가 performance_schema 에 보낸 문장은 제외)
SELECT COUNT_STAR, SCHEMA_NAME, LEFT(DIGEST_TEXT, 100) AS digest_text
  FROM performance_schema.events_statements_summary_by_digest
 WHERE COUNT_STAR > 0
   AND (DIGEST_TEXT IS NULL OR DIGEST_TEXT NOT LIKE '%performance_schema%')
 ORDER BY COUNT_STAR DESC, DIGEST_TEXT;
