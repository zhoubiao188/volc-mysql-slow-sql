#!/usr/bin/env bash
# ============================================================
# 全量压测：从 0 索引到联合索引，再到深分页 / 索引失效 / 连接池
# 用法：bash bench/bench.sh        （需先 bash scripts/start.sh）
# 结果同时写入 logs/bench-result.txt
# ============================================================
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
H=http://127.0.0.1:18100
LOG="$ROOT/logs/bench-result.txt"
mkdir -p "$ROOT/logs"
: > "$LOG"
CITY='%E6%9D%AD%E5%B7%9E'

MY() { mysql -h127.0.0.1 -P3307 -uroot --default-character-set=utf8mb4 -N -e "$1" volc_shop; }
say() { echo "$*" | tee -a "$LOG"; }
run() { java "$ROOT/bench/LoadGen.java" --host="$H" "$@" 2>&1 | tee -a "$LOG"; }
one() { # 单请求耗时（跑 5 次取中位）
  local path="$1"
  for i in 1 2 3 4 5; do
    curl -s -o /dev/null -w "%{time_total}\n" "$H$path"
  done | sort -n | sed -n '3p'
}
drop_indexes() {
  for ix in $(MY "SELECT DISTINCT index_name FROM information_schema.statistics
                  WHERE table_schema='volc_shop' AND table_name='product' AND index_name<>'PRIMARY'"); do
    MY "ALTER TABLE product DROP INDEX $ix" >/dev/null
  done
}

say "================ 机器 ================"
say "$(sysctl -n machdep.cpu.brand_string) / $(sysctl -n hw.ncpu) 核 / $(java -version 2>&1 | head -1)"
say "$(mysql -h127.0.0.1 -P3307 -uroot -N -e 'SELECT CONCAT("MySQL ", VERSION(), " ", @@innodb_buffer_pool_size/1024/1024, "MB buffer pool")')"
say "product 行数：$(MY 'SELECT COUNT(*) FROM product')；在售+杭州：$(MY "SELECT COUNT(*) FROM product WHERE status=1 AND city='杭州'")"

# ---------------- S0：一张没有二级索引的表 ----------------
drop_indexes
say ""
say "================ S0 无二级索引（线上现状）================"
say "SHOW INDEX: $(MY "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='volc_shop' AND table_name='product' AND index_name<>'PRIMARY'") 个二级索引"
say "单请求 列表首页20条: $(one "/api/products?city=$CITY&offset=0&size=20")s"
run --path="/api/products?city=$CITY&offset=0&size=20" --threads=8  --seconds=10
run --path="/api/products?city=$CITY&offset=0&size=20" --threads=32 --seconds=10
run --path="/api/products?city=$CITY&offset=0&size=20" --threads=64 --seconds=10

# ---------------- S1：只加一个单列索引 ----------------
MY "CREATE INDEX idx_status ON product(status)" >/dev/null
say ""
say "================ S1 单列索引 idx_status(status) ================"
say "EXPLAIN: $(MY "EXPLAIN SELECT id,title,price FROM product WHERE status=1 AND city='杭州' ORDER BY created_at DESC LIMIT 20" | awk -F'\t' '{print "type="$5" key="$7" rows="$10" extra="$12}')"
say "单请求: $(one "/api/products?city=$CITY&offset=0&size=20")s"
run --path="/api/products?city=$CITY&offset=0&size=20" --threads=8  --seconds=10
run --path="/api/products?city=$CITY&offset=0&size=20" --threads=32 --seconds=10

# ---------------- S2：联合索引 ----------------
MY "ALTER TABLE product DROP INDEX idx_status, ADD INDEX idx_st_city_ctime(status, city, created_at)" >/dev/null
say ""
say "================ S2 联合索引 (status, city, created_at) ================"
say "EXPLAIN: $(MY "EXPLAIN SELECT id,title,price FROM product WHERE status=1 AND city='杭州' ORDER BY created_at DESC LIMIT 20" | awk -F'\t' '{print "type="$5" key="$7" rows="$10" extra="$12}')"
say "单请求: $(one "/api/products?city=$CITY&offset=0&size=20")s"
run --path="/api/products?city=$CITY&offset=0&size=20" --threads=8  --seconds=10
run --path="/api/products?city=$CITY&offset=0&size=20" --threads=32 --seconds=10
run --path="/api/products?city=$CITY&offset=0&size=20" --threads=64 --seconds=10

# ---------------- S3：深分页 ----------------
say ""
say "================ S3 深分页 offset=100000 ================"
ANCHOR=$(MY "SELECT DATE(created_at) FROM product WHERE status=1 AND city='杭州' ORDER BY created_at DESC LIMIT 1 OFFSET 100000")
say "游标锚点（第 10 万条的时间）= $ANCHOR"
say "offset 分页 单请求: $(one "/api/products?city=$CITY&offset=100000&size=20&strategy=offset")s"
say "延迟关联   单请求: $(one "/api/products?city=$CITY&offset=100000&size=20&strategy=late")s"
say "游标(现算锚点)单请求: $(one "/api/products?city=$CITY&offset=100000&size=20&strategy=cursor")s"
say "游标(锚点由前端带)单请求: $(one "/api/products?city=$CITY&size=20&strategy=cursor&anchor=$ANCHOR")s"
run --path="/api/products?city=$CITY&offset=100000&size=20&strategy=offset" --threads=8 --seconds=10
run --path="/api/products?city=$CITY&offset=100000&size=20&strategy=late"   --threads=8 --seconds=10
run --path="/api/products?city=$CITY&size=20&strategy=cursor&anchor=$ANCHOR" --threads=8 --seconds=10

# ---------------- S4：三种索引失效 ----------------
MY "CREATE INDEX idx_city_code ON product(city_code)" >/dev/null
MY "CREATE INDEX idx_ctime ON product(created_at)" >/dev/null
say ""
say "================ S4 三种索引失效 ================"
say "隐式转换 city_code=330100 (数字) : $(one "/api/products/cast?value=330100&asNumber=true")s"
say "隐式转换 city_code='330100'(字符串): $(one "/api/products/cast?value=330100&asNumber=false")s"
say "函数包裹 DATE(created_at)=?      : $(one "/api/products/func?day=2026-11-01&wrapped=true")s"
say "范围替代 created_at>=? AND < ?  : $(one "/api/products/func?day=2026-11-01&wrapped=false")s"
say "缺前导列 WHERE city=? COUNT(*)   : $(one "/api/products/citycount?city=$CITY")s"
say "cast  EXPLAIN: $(MY "EXPLAIN SELECT id FROM product WHERE city_code=330100 LIMIT 20" | awk -F'\t' '{print "type="$5" key="$7" rows="$10" extra="$12}')"
say "castS EXPLAIN: $(MY "EXPLAIN SELECT id FROM product WHERE city_code='330100' LIMIT 20" | awk -F'\t' '{print "type="$5" key="$7" rows="$10" extra="$12}')"
say "func  EXPLAIN: $(MY "EXPLAIN SELECT id,price FROM product WHERE DATE(created_at)='2026-11-01' ORDER BY created_at DESC LIMIT 20" | awk -F'\t' '{print "type="$5" key="$7" rows="$10" extra="$12}')"
say "range EXPLAIN: $(MY "EXPLAIN SELECT id,price FROM product WHERE created_at>='2026-11-01' AND created_at<'2026-11-02' ORDER BY created_at DESC LIMIT 20" | awk -F'\t' '{print "type="$5" key="$7" rows="$10" extra="$12}')"
say "nopref EXPLAIN: $(MY "EXPLAIN SELECT COUNT(*) FROM product WHERE city='杭州'" | awk -F'\t' '{print "type="$5" key="$7" rows="$10" extra="$12}')"

# ---------------- S5：连接池 ----------------
say ""
say "================ S5 连接池大小（64 并发打首页）================"
for P in 10 32 64; do
  curl -s "$H/admin/pool?size=$P" >/dev/null
  sleep 2
  echo -n "pool=$P  " | tee -a "$LOG"
  run --path="/api/products?city=$CITY&offset=0&size=20" --threads=64 --seconds=10
done
curl -s "$H/admin/pool?size=32" >/dev/null

# ---------------- S6：总数 count ----------------
say ""
say "================ S6 分页总数 count(*) ================"
say "精确 count 单请求: $(one "/api/products/count?city=$CITY&capped=false")s"
say "封顶 count 单请求: $(one "/api/products/count?city=$CITY&capped=true")s"
run --path="/api/products/count?city=$CITY&capped=false" --threads=16 --seconds=10
run --path="/api/products/count?city=$CITY&capped=true"  --threads=16 --seconds=10

say ""
say "================ S7 连接池 × 慢 SQL（回到无索引，32 并发）================"
drop_indexes
for P in 10 32; do
  curl -s "$H/admin/pool?size=$P" >/dev/null
  sleep 1
  echo -n "慢查询 pool=$P  " | tee -a "$LOG"
  run --path="/api/products?city=$CITY&offset=0&size=20" --threads=32 --seconds=10
done
MY "CREATE INDEX idx_st_city_ctime ON product(status, city, created_at)" >/dev/null
curl -s "$H/admin/pool?size=32" >/dev/null

# ---------------- S8：一次请求到底扫了多少行 ----------------
scanrows() { curl -s "$H/admin/dbstats" | grep -o '"handler_read_rnd_next":[0-9]*' | cut -d: -f2; }
say ""
say "================ S8 单次请求的扫描行数（Handler_read_rnd_next 增量）================"
drop_indexes
B=$(scanrows); curl -s -o /dev/null "$H/api/products?city=$CITY&offset=0&size=20"; A=$(scanrows)
say "无索引  单请求扫描行数: $((A - B))"
MY "CREATE INDEX idx_st_city_ctime ON product(status, city, created_at)" >/dev/null
B=$(scanrows); curl -s -o /dev/null "$H/api/products?city=$CITY&offset=0&size=20"; A=$(scanrows)
say "联合索引 单请求扫描行数: $((A - B))"

say ""
say "================ 完成 ================"
