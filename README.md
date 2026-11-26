# volc-mysql-slow-sql

> 火山引擎云数据库 MySQL 版实战：400 万行商品表，一个「看起来对」的索引让接口从 1.1 秒变成 3.2 秒。

一个零框架的 Java 商品列表服务（JDK HttpServer + HikariCP + Connector/J），配一套可复现的压测脚本，
把「商品列表页慢查询」从 1.1 秒压到 1.6 毫秒，并顺手踩穿 4 个索引/分页/连接池的坑。

## 一、实测结论（Apple M1 Pro 8 核 / MySQL 8.4.13 / buffer pool 512MB / 表 4,091,904 行）

查询：`SELECT ... FROM product WHERE status=1 AND city='杭州' ORDER BY created_at DESC LIMIT 20`
（在售 + 杭州 = 110,487 行，占全表 2.7%）

| 阶段 | 索引 | 单请求 | 8 并发 QPS | 8 并发 P99 | 32 并发 P99 |
|---|---|---|---|---|---|
| S0 | 无（线上现状） | **1129 ms** | 4.7 | 1898 ms | 6762 ms |
| S1 | `idx_status(status)` | **3274 ms** ⚠️ | 0.4 | 21931 ms | 全部超时 |
| S2 | `idx_st_city_ctime(status, city, created_at)` | **1.6 ms** | 5219 | 3 ms | 34 ms |

单次请求扫描行数：无索引 **4,091,916** 行 → 联合索引 **11** 行（`Handler_read_rnd_next` 增量）。

深分页（第 5000 页，offset=100000）：

| 写法 | 单请求 | 8 并发 QPS | 8 并发 P99 |
|---|---|---|---|
| `LIMIT 20 OFFSET 100000` | 195 ms | 13.7 | 675 ms |
| 延迟关联（JOIN 子查询） | 25 ms | 244 | 67 ms |
| 游标（锚点由前端带上） | **1.4 ms** | 4724 | 3 ms |

索引失效（都是生产里真实出现过的写法）：

| 写法 | 耗时 | EXPLAIN |
|---|---|---|
| `city_code = 330100`（列是 varchar） | 193 ms | `type=index`, 3,966,648 行 |
| `city_code = '330100'` | 0.95 ms | `type=ref`, 243,812 行 |
| `DATE(created_at) = '2026-11-01'` | 239 ms | `type=index` 全索引扫 |
| `created_at >= ? AND created_at < ?` | 1.43 ms | `type=range`, 5,607 行 |
| `WHERE city=?` 单条件 `COUNT(*)` | 28.5 ms | `Using index for skip scan`（8.0 的 skip scan 救了它，但仍跳读 396,664 次） |

连接池（16 核以下别乱调）：

| 场景 | pool=10 | pool=32 | pool=64 |
|---|---|---|---|
| 快查询（1.6ms）× 64 并发 | 5196 QPS / P99 141ms | 5239 / 134 | 4968 / 144 |
| 慢查询（1.1s）× 32 并发 | 5.4 QPS / P50 5838ms | 5.3 / 5956 | — |

分页总数：

| 写法 | 单请求 | 16 并发 QPS | P99 |
|---|---|---|---|
| `SELECT COUNT(*)` 精确 | 18.4 ms | 330 | 172 ms |
| 封顶 `LIMIT 10001` | **2.8 ms** | 2614 | 126 ms |

## 二、跑起来

```bash
# 1. 起一个独立 MySQL（3307 端口，数据目录 /tmp/volc-mysql-data，不动本机 3306）
bash scripts/start-db.sh

# 2. 打包 + 起应用（连接池默认 10）
bash scripts/start.sh

# 3. 全量压测（约 5 分钟，结果写 logs/bench-result.txt）
bash bench/bench.sh

# 4. 停
bash scripts/stop.sh
```

单条接口手工试：

```bash
curl "http://127.0.0.1:18100/api/products?city=杭州&offset=0&size=20"
curl "http://127.0.0.1:18100/api/products/deep?offset=100000&strategy=cursor&city=杭州"
curl "http://127.0.0.1:18100/admin/explain?type=list"     # 抓 EXPLAIN
curl "http://127.0.0.1:18100/admin/dbstats"               # Handler_read_* 等
```

## 三、代码结构

```
src/main/java/com/example/volcmysql/
├── App.java          # 路由（列表页 / 深分页 / 索引失效变体 / EXPLAIN / 指标）
├── Db.java           # HikariCP 连接池，支持运行时 resize
├── ProductDao.java   # 全部 SQL 都在这，含正确写法与错误写法对照
└── Stats.java        # 接口侧指标
bench/
├── LoadGen.java      # 零依赖压测器：QPS / P50 / P95 / P99
└── bench.sh          # S0~S8 全流程，含索引建/删
sql/
├── schema.sql        # 400 万行表结构（故意不加二级索引）
├── seed.sql          # 确定性取模造数，两次跑分布完全一致
└── index.sql         # 最终索引 + 上线 DDL
```

## 四、核心索引

```sql
-- 一个索引同时满足：两个等值条件 + 一个排序列 + LIMIT 提前终止
ALTER TABLE product ADD INDEX idx_st_city_ctime (status, city, created_at);
```

顺序不是拍脑袋：`status`/`city` 是等值条件（放前面），`created_at` 是范围/排序（放最后）。
反过来写 `(created_at, status, city)` 就退化成全索引扫描 + filesort。

## 五、对应到火山引擎云数据库 MySQL 版

| 本地实验 | 云上对应 |
|---|---|
| 本机 `mysqld --port=3307` | 云数据库 MySQL 版实例（内网地址 + 白名单） |
| `slow_query_log` / `long_query_time` | 控制台慢日志（可按耗时排序、给索引建议） |
| 手工 `ADD INDEX` | DDL 走控制台/工单，大表用 online DDL，避开高峰 |
| 第二个 mysqld 当只读 | 只读实例（读多写少的列表页可以整体指过去） |
| HikariCP pool=10/32 | 连接数受实例规格的 `max_connections` 约束，池子总量 < 该值 |

详细分析见 `docs/article.md`。
