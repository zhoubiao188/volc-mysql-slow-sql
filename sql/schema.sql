-- ============================================================
-- 火山引擎云数据库 MySQL 版 · 商品列表慢 SQL 实验
-- 表结构与线上一致：一张宽表，字段多、基数低、写入频繁
-- ============================================================
DROP TABLE IF EXISTS product;

CREATE TABLE product (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  title       VARCHAR(120)    NOT NULL COMMENT '商品标题',
  category_id INT             NOT NULL COMMENT '类目',
  city        VARCHAR(32)     NOT NULL COMMENT '发货城市（中文，基数低）',
  city_code   VARCHAR(12)     NOT NULL COMMENT '行政区划码，注意是 varchar',
  status      TINYINT         NOT NULL COMMENT '0下架 1在售 2审核中',
  price       DECIMAL(10,2)   NOT NULL,
  stock       INT             NOT NULL,
  seller_id   BIGINT          NOT NULL,
  created_at  DATETIME        NOT NULL COMMENT '上架时间，列表默认按它倒序',
  updated_at  DATETIME        NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品主表（300 万行，故意不加任何二级索引）';
