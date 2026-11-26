#!/usr/bin/env bash
# 起一个独立的 MySQL 8.4 实例（端口 3307，数据目录 /tmp/volc-mysql-data）
# 不碰本机已有的 3306 实例，随时可以整个删掉重来
set -uo pipefail
BASE=/opt/homebrew/opt/mysql@8.4
DATA=/tmp/volc-mysql-data
PORT=3307
ROOT=$(cd "$(dirname "$0")/.." && pwd)
export PATH="$BASE/bin:$PATH"

if mysqladmin -h127.0.0.1 -P$PORT -uroot ping >/dev/null 2>&1; then
  echo "mysql already up on $PORT"
else
  [ -d "$DATA/mysql" ] || mysqld --initialize-insecure --datadir="$DATA" --basedir="$BASE" \
      --log-error=/tmp/volc-mysql-init.log
  mysqld --datadir="$DATA" --basedir="$BASE" --port=$PORT --socket=/tmp/volc-mysql.sock \
         --mysqlx=0 --daemonize --log-error=/tmp/volc-mysql.err --pid-file=/tmp/volc-mysql.pid \
         --max-connections=500 --innodb-buffer-pool-size=512M
  for i in $(seq 1 30); do
    mysqladmin -h127.0.0.1 -P$PORT -uroot ping >/dev/null 2>&1 && break
    sleep 1
  done
  echo "mysql started on $PORT"
fi

mysql -h127.0.0.1 -P$PORT -uroot -e "CREATE DATABASE IF NOT EXISTS volc_shop DEFAULT CHARSET utf8mb4"
CNT=$(mysql -h127.0.0.1 -P$PORT -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='volc_shop' AND table_name='product'")
if [ "${CNT:-0}" = "0" ]; then
  echo "seeding 400w rows ..."
  mysql -h127.0.0.1 -P$PORT -uroot --default-character-set=utf8mb4 volc_shop < "$ROOT/sql/schema.sql"
  mysql -h127.0.0.1 -P$PORT -uroot --default-character-set=utf8mb4 volc_shop < "$ROOT/sql/seed.sql"
fi
mysql -h127.0.0.1 -P$PORT -uroot --default-character-set=utf8mb4 -e "SELECT COUNT(*) AS rows_in_product FROM volc_shop.product"
