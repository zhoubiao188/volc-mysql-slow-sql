-- ============================================================
-- 上线 DDL：三张表各取所需，这里只针对商品列表页
-- 顺序原则：等值条件在前，范围/排序列在后
-- ============================================================

-- 1) 列表页主索引：两个等值条件 + 一个排序列，LIMIT 20 能提前终止
ALTER TABLE product ADD INDEX idx_st_city_ctime (status, city, created_at),
  ALGORITHM = INPLACE, LOCK = NONE;

-- 2) 如果还要按"某个城市 + 某类目"筛，把 category_id 插在 status 后面，
--    注意不要为每一种查询组合都建一个索引
-- ALTER TABLE product ADD INDEX idx_st_cat_city_ctime (status, category_id, city, created_at);

-- 3) city_code 上确实有查询时，才建这个索引；
--    建了也要保证 Java 侧传的是 String —— 传 int 会整体失效
ALTER TABLE product ADD INDEX idx_city_code (city_code),
  ALGORITHM = INPLACE, LOCK = NONE;

-- 4) 按时间范围拉数据的报表类查询
ALTER TABLE product ADD INDEX idx_ctime (created_at),
  ALGORITHM = INPLACE, LOCK = NONE;

-- 自检：确认索引被用上，且没有 filesort
EXPLAIN SELECT id, title, price, stock, seller_id, created_at
FROM product WHERE status = 1 AND city = '杭州'
ORDER BY created_at DESC LIMIT 20;
-- 期望：type=ref  key=idx_st_city_ctime  Extra=Backward index scan
