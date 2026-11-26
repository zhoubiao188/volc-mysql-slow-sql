#!/usr/bin/env bash
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
[ -f "$ROOT/logs/app.pid" ] && kill "$(cat "$ROOT/logs/app.pid")" 2>/dev/null && echo "app stopped"
pkill -f "volc-mysql-slow-sql.jar" 2>/dev/null
/opt/homebrew/opt/mysql@8.4/bin/mysqladmin -h127.0.0.1 -P3307 -uroot shutdown 2>/dev/null && echo "mysql stopped"
exit 0
