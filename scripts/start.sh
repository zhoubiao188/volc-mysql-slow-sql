#!/usr/bin/env bash
# 起应用（默认连接池 10，和 HikariCP 默认值一致）
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
POOL=${POOL:-10}
PORT=${PORT:-18100}
mkdir -p "$ROOT/logs"
if [ ! -f "$ROOT/target/volc-mysql-slow-sql.jar" ]; then
  (cd "$ROOT" && mvn -q -DskipTests package) || exit 1
fi
java -jar "$ROOT/target/volc-mysql-slow-sql.jar" --port=$PORT --pool=$POOL \
  > "$ROOT/logs/app.log" 2>&1 &
echo $! > "$ROOT/logs/app.pid"
for i in $(seq 1 20); do
  curl -s "http://127.0.0.1:$PORT/health" >/dev/null && break
  sleep 0.5
done
curl -s "http://127.0.0.1:$PORT/health"; echo
curl -s "http://127.0.0.1:$PORT/admin/pool"; echo
