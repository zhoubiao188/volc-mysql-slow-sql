-- ============================================================
-- 造数：一次 INSERT 铺 400 万行
-- 用确定性取模代替 RAND()：
--   1) 可复现，两次跑出来的数据分布完全一样，压测数字才有可比性
--   2) MySQL 在派生表里对 RAND() 的求值很容易退化成每行同值，
--      直接用 k 取模更稳
-- 分布按生产倾斜：
--   status=1 占 90%；city 按业务量（杭州只占 3%）
--   → 「在售 + 杭州」命中率约 2.7%，典型的低选择性 + 排序场景
--   关键：city 用 k%100，status 用 (k DIV 100)%100，两个模数错开，
--   否则会变成「杭州的商品全都在售」这种假数据
-- ============================================================
SET SESSION cte_max_recursion_depth = 10000;

TRUNCATE TABLE product;

INSERT INTO product (title, category_id, city, city_code, status, price, stock, seller_id, created_at, updated_at)
WITH RECURSIVE
  hi(n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM hi WHERE n < 4095),
  lo(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM lo WHERE n < 999)
SELECT
  CONCAT('商品-', LPAD(k, 7, '0')),
  1 + (k % 60),
  CASE WHEN k % 100 < 3  THEN '杭州' WHEN k % 100 < 13 THEN '北京' WHEN k % 100 < 25 THEN '上海'
       WHEN k % 100 < 33 THEN '广州' WHEN k % 100 < 42 THEN '深圳' WHEN k % 100 < 48 THEN '成都'
       WHEN k % 100 < 53 THEN '武汉' WHEN k % 100 < 57 THEN '南京' WHEN k % 100 < 61 THEN '西安'
       WHEN k % 100 < 65 THEN '重庆' WHEN k % 100 < 77 THEN '苏州' WHEN k % 100 < 89 THEN '长沙'
       ELSE '郑州' END,
  CASE WHEN k % 100 < 3  THEN '330100' WHEN k % 100 < 13 THEN '110100' WHEN k % 100 < 25 THEN '310100'
       WHEN k % 100 < 33 THEN '440100' WHEN k % 100 < 42 THEN '440300' WHEN k % 100 < 48 THEN '510100'
       WHEN k % 100 < 53 THEN '420100' WHEN k % 100 < 57 THEN '320100' WHEN k % 100 < 61 THEN '610100'
       WHEN k % 100 < 65 THEN '500100' WHEN k % 100 < 77 THEN '320500' WHEN k % 100 < 89 THEN '430100'
       ELSE '410100' END,
  CASE WHEN (k DIV 100) % 100 < 90 THEN 1 WHEN (k DIV 100) % 100 < 98 THEN 0 ELSE 2 END,
  ROUND(9.9 + ((k * 37) % 4990), 2),
  (k * 13) % 5000,
  10000 + (k % 5000),
  NOW() - INTERVAL ((k * 7919) % 63072000) SECOND,
  NOW()
FROM (SELECT lo.n * 4096 + hi.n AS k FROM lo CROSS JOIN hi) x;

ANALYZE TABLE product;

SELECT COUNT(*) AS total, SUM(status = 1) AS on_sale,
       SUM(city = '杭州') AS hangzhou,
       SUM(status = 1 AND city = '杭州') AS hangzhou_on_sale,
       MIN(created_at) AS min_ct, MAX(created_at) AS max_ct
FROM product;
