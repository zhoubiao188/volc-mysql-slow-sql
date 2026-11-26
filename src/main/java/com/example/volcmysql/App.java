package com.example.volcmysql;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 商品列表服务。零框架，JDK 自带 HttpServer —— 压测数字里不掺框架的水分。
 *
 * 接口（都能在压测里单独打）：
 *   /api/products?city=杭州&offset=0&size=20        列表页（offset 分页）
 *   /api/products/deep?city=杭州&offset=100000&strategy=offset|late|cursor
 *   /api/products/cast?value=330100&asNumber=true   隐式类型转换
 *   /api/products/func?day=2026-11-01&wrapped=true  字段套函数
 *   /api/products/citycount?city=杭州                缺前导列
 *   /api/products/count?city=杭州&capped=true        精确 count / 封顶 count
 *   /admin/explain?type=list|deep|late|cursor|cast|func|citycount|count
 *   /admin/pool?size=32                             运行时调连接池
 *   /admin/stats | /admin/dbstats | /health
 */
public class App {

    static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    public static void main(String[] args) throws Exception {
        int port = 18100;
        int pool = 10;
        String url = "jdbc:mysql://127.0.0.1:3307/volc_shop?useSSL=false&characterEncoding=utf8"
                + "&rewriteBatchedStatements=true&useServerPrepStmts=false";
        String user = "root";
        String pass = "";

        for (String a : args) {
            int i = a.indexOf('=');
            if (i < 0) {
                continue;
            }
            String k = a.substring(0, i);
            String v = a.substring(i + 1);
            switch (k) {
                case "--port" -> port = Integer.parseInt(v);
                case "--pool" -> pool = Integer.parseInt(v);
                case "--url" -> url = v;
                case "--user" -> user = v;
                case "--pass" -> pass = v;
                default -> {
                }
            }
        }

        Db db = new Db(url, user, pass, pool);
        ProductDao dao = new ProductDao(db);
        Stats stats = new Stats();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 512);
        server.setExecutor(Executors.newFixedThreadPool(200));

        // 主查询：列表页
        server.createContext("/api/products", ex -> {
            Map<String, String> q = query(ex);
            long t0 = System.nanoTime();
            String strategy = q.getOrDefault("strategy", "offset");
            int offset = Integer.parseInt(q.getOrDefault("offset", "0"));
            int size = Integer.parseInt(q.getOrDefault("size", "20"));
            String city = q.getOrDefault("city", "杭州");
            try {
                List<ProductDao.Product> rows;
                switch (strategy) {
                    case "late" -> rows = dao.deepLateJoin(city, size, offset);
                    case "cursor" -> {
                        String anchor = q.get("anchor");
                        if (anchor == null || anchor.isEmpty()) {
                            anchor = dao.cursorAnchor(city, offset);
                        }
                        rows = dao.cursor(city, anchor, size);
                    }
                    default -> rows = dao.list(city, size, offset);
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("strategy", strategy);
                body.put("offset", offset);
                body.put("rows", rows.size());
                body.put("data", rows);
                ok(ex, t0, stats, body, rows.size());
            } catch (Exception e) {
                fail(ex, t0, stats, e);
            }
        });

        // 深分页
        server.createContext("/api/products/deep", ex -> {
            Map<String, String> q = query(ex);
            long t0 = System.nanoTime();
            int offset = Integer.parseInt(q.getOrDefault("offset", "100000"));
            int size = Integer.parseInt(q.getOrDefault("size", "20"));
            String city = q.getOrDefault("city", "杭州");
            String strategy = q.getOrDefault("strategy", "offset");
            try {
                List<ProductDao.Product> rows = switch (strategy) {
                    case "late" -> dao.deepLateJoin(city, size, offset);
                    case "cursor" -> dao.cursor(city, dao.cursorAnchor(city, offset), size);
                    default -> dao.list(city, size, offset);
                };
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("strategy", strategy);
                body.put("offset", offset);
                body.put("rows", rows.size());
                ok(ex, t0, stats, body, rows.size());
            } catch (Exception e) {
                fail(ex, t0, stats, e);
            }
        });

        // 隐式类型转换
        server.createContext("/api/products/cast", ex -> {
            Map<String, String> q = query(ex);
            long t0 = System.nanoTime();
            try {
                boolean asNumber = Boolean.parseBoolean(q.getOrDefault("asNumber", "true"));
                int n = dao.castQuery(q.getOrDefault("value", "330100"), asNumber);
                ok(ex, t0, stats, Map.of("asNumber", asNumber, "matched", n), n);
            } catch (Exception e) {
                fail(ex, t0, stats, e);
            }
        });

        // 字段套函数
        server.createContext("/api/products/func", ex -> {
            Map<String, String> q = query(ex);
            long t0 = System.nanoTime();
            try {
                boolean wrapped = Boolean.parseBoolean(q.getOrDefault("wrapped", "true"));
                int n = dao.funcQuery(q.getOrDefault("day", "2026-11-01"), wrapped);
                ok(ex, t0, stats, Map.of("wrapped", wrapped, "rows", n), n);
            } catch (Exception e) {
                fail(ex, t0, stats, e);
            }
        });

        // 缺前导列
        server.createContext("/api/products/citycount", ex -> {
            Map<String, String> q = query(ex);
            long t0 = System.nanoTime();
            try {
                long n = dao.noPrefixCount(q.getOrDefault("city", "杭州"));
                ok(ex, t0, stats, Map.of("count", n), 1);
            } catch (Exception e) {
                fail(ex, t0, stats, e);
            }
        });

        // 总数
        server.createContext("/api/products/count", ex -> {
            Map<String, String> q = query(ex);
            long t0 = System.nanoTime();
            try {
                boolean capped = Boolean.parseBoolean(q.getOrDefault("capped", "false"));
                long n = dao.count(q.getOrDefault("city", "杭州"), capped);
                ok(ex, t0, stats, Map.of("capped", capped, "count", n), 1);
            } catch (Exception e) {
                fail(ex, t0, stats, e);
            }
        });

        // 执行计划
        server.createContext("/admin/explain", ex -> {
            Map<String, String> q = query(ex);
            String type = q.getOrDefault("type", "list");
            String city = q.getOrDefault("city", "杭州");
            int offset = Integer.parseInt(q.getOrDefault("offset", "0"));
            String sql = switch (type) {
                case "deep" -> fill(ProductDao.LIST_SQL, city, 20, offset);
                case "late" -> fill(ProductDao.DEEP_LATE_JOIN_SQL, city, 20, offset);
                case "cursor" -> "SELECT id, title, price, stock, seller_id, created_at FROM product "
                        + "WHERE status = 1 AND city = '" + city + "' AND created_at < '2026-01-01 00:00:00' "
                        + "ORDER BY created_at DESC LIMIT 20";
                case "cast" -> ProductDao.CAST_NUM_SQL.replace("?", "330100");
                case "castStr" -> ProductDao.CAST_STR_SQL.replace("?", "'330100'");
                case "func" -> ProductDao.FUNC_SQL.replace("?", "'2026-11-01'");
                case "funcRange" -> ProductDao.FUNC_RANGE_SQL
                        .replaceFirst("\\?", "'2026-11-01'").replace("?", "'2026-11-01 23:59:59'");
                case "citycount" -> ProductDao.NO_PREFIX_SQL.replace("?", "'" + city + "'");
                case "count" -> ProductDao.COUNT_SQL.replace("?", "'" + city + "'");
                case "capped" -> ProductDao.COUNT_CAPPED_SQL.replace("?", "'" + city + "'");
                default -> fill(ProductDao.LIST_SQL, city, 20, offset);
            };
            try {
                String plan = dao.explain(sql);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("sql", sql);
                body.put("plan", plan);
                send(ex, 200, body);
            } catch (Exception e) {
                send(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
            }
        });

        server.createContext("/admin/stats", ex -> send(ex, 200, stats.snapshot()));

        server.createContext("/admin/dbstats", ex -> send(ex, 200, db.serverStat()));

        server.createContext("/admin/pool", ex -> {
            Map<String, String> q = query(ex);
            if (q.containsKey("size")) {
                db.resize(Integer.parseInt(q.get("size")));
            }
            send(ex, 200, db.poolStat());
        });

        server.createContext("/health", ex -> send(ex, 200, Map.of("ok", true)));

        server.start();
        System.out.printf("started port=%d pool=%d url=%s%n", port, pool, url);
    }

    private static String fill(String sql, String city, int size, int offset) {
        return sql.replaceFirst("\\?", "'" + city + "'").replaceFirst("\\?", String.valueOf(size))
                .replaceFirst("\\?", String.valueOf(offset));
    }

    private static void ok(HttpExchange ex, long t0, Stats stats, Map<String, Object> body, int rows) throws java.io.IOException {
        long cost = (System.nanoTime() - t0) / 1_000_000;
        stats.hit(cost, rows, false);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("costMs", cost);
        resp.put("body", body);
        send(ex, 200, resp);
    }

    private static void fail(HttpExchange ex, long t0, Stats stats, Exception e) throws java.io.IOException {
        long cost = (System.nanoTime() - t0) / 1_000_000;
        stats.hit(cost, 0, true);
        send(ex, 500, Map.of("costMs", cost, "error", String.valueOf(e.getMessage())));
    }

    private static void send(HttpExchange ex, int code, Object body) throws java.io.IOException {
        byte[] b = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return m;
        }
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) {
                m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }
}
