给一张 400 万行的商品表加索引，接口从 1.1 秒变成 3.2 秒。

这不是段子，是我上周真实做出来的一次事故。加的那个索引，单看每个字段都没毛病——`status` 在 WHERE 里，业务上天天用，建个索引天经地义。可它就是让接口慢了 3 倍，并发一上来直接雪崩。

后来把索引换成三列的联合索引，同一条 SQL、同一份代码，接口从 1129 ms 掉到 1.6 ms。

这篇文章把整个过程完整还原：怎么量、怎么判断、四个坑分别长什么样、最后怎么在火山引擎云数据库 MySQL 版上落地。所有数字都是本机实测，脚本在仓库里，你可以自己跑一遍。

## 一、先把结论摆出来

被测查询（电商后台商品列表页，最常见的形态）：

```sql
SELECT id, title, price, stock, seller_id, created_at
FROM product
WHERE status = 1 AND city = '杭州'
ORDER BY created_at DESC
LIMIT 20;
```

表 4,091,904 行，命中条件的是 110,487 行（2.7%）。

| 阶段 | 索引 | 单请求 | 8 并发 QPS | 8 并发 P99 | 32 并发 P99 |
|---|---|---|---|---|---|
| S0 | 无（线上现状） | 1129 ms | 4.7 | 1898 ms | 6762 ms |
| S1 | `idx_status(status)` | **3274 ms** | 0.4 | 21931 ms | 全部超时 |
| S2 | `idx_st_city_ctime(status, city, created_at)` | **1.6 ms** | 5219 | 3 ms | 34 ms |

一句话版本：**索引不是"有"就好，是"对"才好。给低选择性的单列建索引，比不建还糟。**

## 二、复现环境

刻意做成最小的可复现单元，不掺框架的水分：

- 机器：Apple M1 Pro / 8 核，本机 MySQL 8.4.13，`innodb_buffer_pool_size = 512MB`（故意小于表体积，让磁盘 IO 参与进来）
- 服务：JDK 19 + JDK 自带 `HttpServer` + HikariCP + Connector/J，没有任何 Web 框架
- 压测器：一个 200 行的 Java 类，客户端侧统计真实往返耗时，QPS = 完成数 / 墙钟时间
- 造数：确定性取模（不用 `RAND()`），两次跑出来的数据分布一模一样，压测数字才有可比性

造数的分布是照生产捏的：`status=1` 占 90%，`city` 按业务量倾斜——杭州只占 3%、上海 12%、郑州 11%。

```sql
-- 关键：city 用 k%100，status 用 (k DIV 100)%100
-- 两个模数必须错开，否则会造出「杭州的商品全都在售」这种假数据
CASE WHEN k % 100 < 3  THEN '杭州' WHEN k % 100 < 13 THEN '北京' ... END,
CASE WHEN (k DIV 100) % 100 < 90 THEN 1 WHEN (k DIV 100) % 100 < 98 THEN 0 ELSE 2 END,
NOW() - INTERVAL ((k * 7919) % 63072000) SECOND
```

第一版造数我就踩了这个坑：用 `RAND()` 生成分布，结果所有杭州商品全是 `status=1`，"在售 + 杭州" 的选择性直接失真，压测跑出来的结论全是错的。造数脚本比压测脚本更需要 review。

## 三、第 0 步：先量清楚"慢"慢在哪

不要一上来就 `EXPLAIN`，先看两个数：**一次请求扫了多少行**，以及**并发下瓶颈是 CPU 还是 IO**。

我在服务里挂了个 `/admin/dbstats`，直接透出 MySQL 的 `Handler_read_*` 全局计数器，压测前后取差值：

```java
String sql = "SHOW GLOBAL STATUS WHERE Variable_name IN "
    + "('Handler_read_rnd_next','Handler_read_next','Handler_read_key',"
    + "'Select_scan','Sort_rows','Created_tmp_disk_tables','Slow_queries')";
```

结果很干脆：

| 索引状态 | 单次请求扫描行数 |
|---|---|
| 无二级索引 | **4,091,916** |
| 联合索引 | **11** |

40 万倍。这就是全部问题的答案——一个只要 20 行的接口，扫了 409 万行，还把 409 万行拖进 filesort 排了一遍。

S0 的并发表现也印证了瓶颈在 IO 不在 CPU：

```
单请求          1129 ms
 8 并发  →  4.7 QPS   P50 1636ms  P99 1898ms
32 并发  →  5.0 QPS   P50 6244ms  P99 6762ms
64 并发  →  5.0 QPS   P50 12652ms P99 12796ms
```

并发从 8 加到 64，QPS 一动不动卡在 5。这是典型的**排队**而不是**并行**：8 个核对 400 万行的顺序扫描 + 排序，加线程只会让每个请求等更久。64 并发时 P99 已经 12.8 秒——用户早就关页面了，请求还占着连接和 CPU。

## 四、第 1 步：加了个单列索引，接口从 1.1 秒变成 3.2 秒

看到 `status` 在 WHERE 里，第一反应当然是：

```sql
CREATE INDEX idx_status ON product(status);
```

单请求 1129 ms → **3274 ms**。

慢了两倍多。EXPLAIN 是这样的：

```
type=ref  key=idx_status  rows=1983324  Extra=Using where; Using filesort
```

为什么更慢？把这条 SQL 拆成三步就明白了：

1. `status=1` 在 409 万行里占了 **90%**，也就是 368 万行。走 `idx_status` 等于先定位到 368 万个索引项；
2. 索引里没有 `city`、`created_at`，MySQL 只能**逐行回表**——按主键随机读聚簇索引，368 万次随机 IO；
3. 回表拿到 `city` 过滤完剩 11 万行，再 filesort 排序取前 20。

对比一下不加索引：全表顺序扫描是**一次顺序 IO 读到底**，对 SSD 和 buffer pool 都很友好。而 368 万次随机回表，等于把顺序读变成随机读，还多读了索引页。

**低选择性 + 需要回表 = 比全表扫更差。** 这条经验在 MySQL 里几乎永远成立。

并发下的数据更难听：

```
 8 并发  →  0.4 QPS   P50 21543ms  P99 21931ms
32 并发  →  全部超时（32 个请求全部打在 60s 客户端超时上）
```

S1 这段数字是我三次压测里抖动最大的一组（另两次 32 并发跑出过 P50 5~7 秒、部分 500）。原因也清楚：随机回表会把 buffer pool 冲刷得很厉害，命中率全看运气。但**方向是稳定的——三次里每一次 S1 都比 S0 慢**，并发一上来就是雪崩。

顺便说个生产上很常见的连锁反应：慢 SQL 一出现，连接池 10 个连接全被占死，后面的请求 `connectionTimeout`（我设了 5 秒）一到就抛异常，接口直接返回 500。**慢 SQL 从来不会只慢自己，它会顺着连接池把整个服务拖下去。**

## 五、第 2 步：把三个条件塞进一个索引

真正的解法是让索引**自己就能回答完这条 SQL**：

```sql
ALTER TABLE product ADD INDEX idx_st_city_ctime (status, city, created_at);
```

```
type=ref  key=idx_st_city_ctime  rows=203868  Extra=Backward index scan
```

注意 `Extra` 里**没有 filesort 了**，变成了 `Backward index scan`——反向扫描索引就拿到了倒序，`LIMIT 20` 读到 20 行立刻停。

单请求 **1.6 ms**，8 并发 **5219 QPS**，P99 **3 ms**。

字段顺序不是拍脑袋定的，按三条规则排：

1. **等值条件往前放**：`status=?`、`city=?` 都是等值，谁先谁后无所谓，但都要在 `created_at` 前面；
2. **范围/排序放最后**：`created_at` 一旦放在中间，后面的列就用不上了；
3. **让 ORDER BY 能顺着索引用**：`(status, city, created_at)` 的前缀定死之后，第三列天然有序，`ORDER BY created_at DESC` 不需要额外排序。

反过来写 `(created_at, status, city)` 会怎样？索引第一列就是范围，`status`/`city` 用不上，退化回全索引扫描 + filesort，跟没建差不多。

顺便提一句 `city` 的位置：如果业务里还有"只要在售、不限城市"的列表，`(status, city, created_at)` 也能用——只用前缀 `status` 就行，只是要扫的行多些。**一个联合索引通常能覆盖同一类查询的好几种变体，别为每种组合都建一个索引。**

## 六、第 3 步：第 5000 页的深分页，195 ms → 1.4 ms

首页快了，深分页还是老样子。运营真的会翻到第 5000 页（有人在爬你的站，也有人在导出数据）：

| 写法 | 单请求 | 8 并发 QPS | 8 并发 P99 |
|---|---|---|---|
| `LIMIT 20 OFFSET 100000` | 195 ms | 13.7 | 675 ms |
| 延迟关联（JOIN 子查询） | 25 ms | 244 | 67 ms |
| 游标（锚点由前端带上） | **1.4 ms** | 4724 | 3 ms |

`OFFSET 100000` 的问题不在于"跳过 10 万行"，而在于**每一行都要回表**再丢掉。10 万次随机读，纯浪费。

**优化 A：延迟关联。** 先在索引里把 20 个主键捞出来（覆盖索引，不回表），再拿这 20 个 id 回表：

```sql
SELECT p.id, p.title, p.price, p.stock, p.seller_id, p.created_at
FROM product p JOIN (
  SELECT id FROM product WHERE status = 1 AND city = ?
  ORDER BY created_at DESC LIMIT ? OFFSET ?
) t ON p.id = t.id
ORDER BY p.created_at DESC;
```

回表次数从 10 万次降到 20 次，195 ms → 25 ms。

**优化 B：游标（seek method）。** 保留 B+ 树的范围能力，彻底不要 OFFSET：

```sql
SELECT id, title, price, stock, seller_id, created_at
FROM product
WHERE status = 1 AND city = ? AND created_at < ?   -- 上一页最后一条的时间
ORDER BY created_at DESC LIMIT 20;
```

1.4 ms，8 并发 4724 QPS。前提是**锚点必须由调用方带上来**——我这里还提供了一个"服务端现算锚点"的模式（内部 `LIMIT 1 OFFSET 100000` 取边界），结果是 25.6 ms：绕了一圈又回到了 OFFSET 的成本上。这个对比挺说明问题的，**游标分页的收益完全依赖"不要重新计算偏移"这个前提**。

游标的代价也说清楚：不能跳页（只能上一页/下一页）、锚点字段必须唯一有序（时间戳有重复时要加 `id` 做 tie-breaker）。列表页够用，后台的"跳到第 372 页"就不行了——那种场景老老实实用延迟关联。

## 七、第 4 步：三个索引失效的真坑

这三个都是我在生产代码里见过的写法，一个比一个隐蔽。

**坑 1：隐式类型转换。** `city_code` 定义是 `varchar(12)`，Java 里传了个 `int`：

| 写法 | 耗时 | EXPLAIN |
|---|---|---|
| `city_code = 330100`（数字） | 193 ms | `type=index`，扫 3,966,648 行 |
| `city_code = '330100'` | 0.95 ms | `type=ref`，243,812 行 |

203 倍。原因是 MySQL 比较 `varchar` 和数字时，会把**列**转成数字再比——列上套了函数，索引自然失效。注意 `type=index` 不是"用上了索引"，而是"把整个索引从头扫到尾"，比全表扫还多读一遍索引页。

Java 侧就一行代码的差别：

```java
// 错：参数是 int，列是 varchar
ps.setInt(1, 330100);
// 对：参数是 String
ps.setString(1, "330100");
```

**坑 2：字段上套函数。**

| 写法 | 耗时 | EXPLAIN |
|---|---|---|
| `DATE(created_at) = '2026-11-01'` | 239 ms | `type=index` 全索引扫 |
| `created_at >= '2026-11-01' AND created_at < '2026-11-02'` | 1.43 ms | `type=range`，5,607 行 |

167 倍。原则很简单：**等号左边只能是裸列**，任何函数、计算、类型转换都会让索引失效。日期查询改成左闭右开区间，别用 `DATE()`、`TO_DAYS()`、`YEAR()`。

**坑 3：联合索引缺前导列。** 这个我原本以为会全表扫，实测被 MySQL 8 救了一把：

```sql
SELECT COUNT(*) FROM product WHERE city = '杭州';   -- 索引是 (status, city, created_at)
```

EXPLAIN：`type=range  rows=396664  Extra=Using index for skip scan`。

MySQL 8.0.13 起支持 **skip scan**：前导列 `status` 只有 3 个不同值，优化器就自己枚举这 3 个值，每个值下去找 `city='杭州'` 的区间。28.5 ms，对比带上前导列的正常写法 18.4 ms——只慢 1.5 倍，而不是 100 倍。

但我不会因此就放心：`rows=396,664` 意味着 39 万次索引跳读，只是 `COUNT` 走覆盖索引才勉强能看。数据量再翻 10 倍，或者前导列的基数从 3 涨到 300，skip scan 立刻失效。**能写前导列就写上，别赌优化器的聪明。**

## 八、第 5 步：连接池不是慢 SQL 的止痛药

这是我这次最想纠正的一个直觉。很多人看到接口慢，第一反应是"连接池调大"。

**快查询（1.6 ms）下 64 并发：**

| 连接池 | QPS | P50 | P99 |
|---|---|---|---|
| 10 | 5196 | 5 ms | 141 ms |
| 32 | 5239 | 5 ms | 134 ms |
| 64 | 4968 | 5 ms | 144 ms |

三条线几乎重合。**SQL 已经 1.6 ms 了，10 个连接就够用，调大纯属浪费内存。**

**慢查询（1129 ms）下 32 并发：**

| 连接池 | QPS | P50 | P99 |
|---|---|---|---|
| 10 | 5.4 | 5838 ms | 6326 ms |
| 32 | 5.3 | 5956 ms | 6510 ms |

QPS 一毫不动。因为瓶颈在磁盘 IO 和 buffer pool，不在连接数——**多开 22 个连接，只是让更多请求同时排队在随机 IO 上，P99 反而更差。**

所以结论有两面：

- 连接池要跟 SQL 的健康度挂钩。SQL 1.6 ms 时 pool=10 足够；SQL 100 ms 时需要 pool 更大才能撑住 QPS；SQL 1 秒以上，pool 调多大都没用。
- 云上还要看实例规格。火山引擎云数据库 MySQL 版的 `max_connections` 跟规格绑定，多个应用实例的池子加起来不能超过它，否则会出现"应用没满、数据库满了"的诡异现象——`ERROR 1040: Too many connections`。

一个好用的起点：`pool = CPU 核数 × 2 + 有效磁盘数`，然后按 P99 微调，别一把梭到 200。

## 九、第 6 步：那个"共 110,487 条"

分页组件要显示总数，于是每个请求都跟一条 `COUNT(*)`：

| 写法 | 单请求 | 16 并发 QPS | P99 |
|---|---|---|---|
| `SELECT COUNT(*)` 精确 | 18.4 ms | 330 | 172 ms |
| 封顶 `SELECT COUNT(*) FROM (SELECT 1 ... LIMIT 10001) t` | **2.8 ms** | 2614 | 126 ms |

18 ms 单看不算慢，但它是**每个列表请求都带一条**，主查询才 1.6 ms，总数查询反过来成了大头。8 倍差距，P99 从 126 涨到 172。

两个做法，按业务容忍度选：

1. **封顶计数**：只数到 10001，超过就显示"1 万+"。绝大多数 C 端列表页根本不需要精确总数，用户也不会翻到第 500 页。
2. **缓存总数 + 增量维护**：总数变化不频繁时，用 Redis 存，商品上下架时 `+1/-1`。

顺带一提，`SQL_CALC_FOUND_ROWS` 在 MySQL 8.0.17 已废弃，别再用了。

## 十、搬到火山引擎云数据库 MySQL 版上

本地这套实验可以直接映射到云上：

| 本地 | 云上 |
|---|---|
| `mysqld --port=3307` | 云数据库 MySQL 版实例（内网地址 + 白名单） |
| 手工 `ALTER TABLE ADD INDEX` | 控制台 DDL，大表走 online DDL 并避开高峰 |
| `slow_query_log` | 慢日志（按耗时排序，能直接看到扫描行数） |
| 第二个 mysqld 当只读 | 只读实例，列表页这类读多的查询整体指过去 |
| HikariCP pool | 受实例规格 `max_connections` 约束 |

几个我实际踩过或差点踩到的点：

**1. 慢日志是最好的入口。** 云上排查不要先猜，先把 `long_query_time` 调到 0.5 秒，跑一天，看慢日志里哪条 SQL 出现次数最多、`Rows_examined` 最大。我在本地是用 `Handler_read_rnd_next` 增量等效做的，云上慢日志直接给了 `Rows_examined`，更省事。**先看 Rows_examined 和 Rows_sent 的比值**——这个比值超过 100 就基本可以断定索引有问题。

**2. 大表加索引要挑时间。** 400 万行在本地 `ALTER TABLE` 20 秒就完事，线上表如果有千万级 + 高频写入，DDL 期间要盯着主备延迟。云上实例支持 online DDL，但仍然会消耗 IO，放在低峰做。

**3. 索引不是越多越好。** 每多一个二级索引，写入就多一次索引维护。我这篇文章里 S4 为了演示失效建了 `idx_city_code`、`idx_ctime`，生产上不会为了演示就建。经验值：单表二级索引不超过 5 个，写多读少的表更要克制。

**4. 只读实例能救读，但救不了烂 SQL。** 列表页是读多写少的典型，把查询指到只读实例确实能缓解主库压力。但如果 SQL 还是扫 400 万行，只读实例只是把同样的浪费复制了一份——**先修 SQL，再扩实例。**

**5. 上线后盯三个指标，别只盯 P99。** P99 只告诉你"慢了"，不告诉你"为什么慢"。我会同时看：

- `Rows_examined / Rows_sent`：健康值应该在个位数，超过 100 就是索引退化；
- 慢日志条目数：`long_query_time` 设 0.5 秒，每天比条数，涨了就是新 SQL 或索引被优化器放弃了；
- `Handler_read_rnd_next` 增速：这个值持续上升说明有全表扫描在跑。

另外，索引上线的第一周要留回滚通道。删索引比加索引快得多，`DROP INDEX` 秒级完成——**加之前先想好怎么删**。

## 十一、代码与复现

完整工程（含 400 万行造数脚本、零依赖压测器、S0~S8 全流程脚本）：

https://github.com/zhoubiao188/volc-mysql-slow-sql

```bash
bash scripts/start-db.sh    # 独立 MySQL（3307），自动造 400 万行
bash scripts/start.sh       # 起服务（18100 端口，池 10）
bash bench/bench.sh         # 全量压测，约 5 分钟
```

`ProductDao.java` 里正确写法和错误写法是并排放的，方便对照：

```java
// 正确：三个条件一个索引，ORDER BY 顺着索引走
public static final String LIST_SQL =
        "SELECT id, title, price, stock, seller_id, created_at " +
        "FROM product WHERE status = 1 AND city = ? " +
        "ORDER BY created_at DESC LIMIT ? OFFSET ?";

// 错误示范：拼在 SQL 里的 OFFSET，以及能省则省的 COUNT(*)
```

服务里还留了个 `/admin/explain?type=list|deep|late|cursor|cast|func` 接口，压测时随手抓执行计划，比开客户端敲 EXPLAIN 快得多。

## 十二、一句话收尾

这次最大的收获不是"加联合索引"这个动作，而是**三个反直觉的事实**：

1. **加索引可能更慢。** 低选择性单列 + 回表 = 比全表扫更差，这次是 3.2 秒 vs 1.1 秒。
2. **连接池治不了慢 SQL。** 1129 ms 的查询，pool 从 10 调到 32，QPS 一点没涨。
3. **优化器比你以为的聪明，但别依赖它。** skip scan 救了"缺前导列"，可它只是把 100 倍的代价压到 1.5 倍，不是解决问题。

判断顺序永远是：先量扫描行数 → 再看执行计划 → 再改索引 → 最后才动连接池和实例。

如果你的列表页也在 1 秒以上，先去慢日志里按 `Rows_examined` 倒序排一遍，八成就在前三条里。
