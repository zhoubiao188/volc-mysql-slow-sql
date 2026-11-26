package com.example.volcmysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 连接池 + 数据源。
 *
 * 线上对应「火山引擎云数据库 MySQL 版」的连接串：
 *   jdbc:mysql://<实例私网地址>:3306/volc_shop?useSSL=true&...
 * 本地实验用 127.0.0.1:3307（见 scripts/start-db.sh）。
 *
 * 连接池大小是本文的重点之一：HikariCP 默认 10，机器 16 核以上时
 * 慢查询会把 10 个连接全部占死，后面的请求只能排队 —— 表现为
 * "数据库 CPU 不高，但接口全在超时"。runntime 可调，压测时直接改。
 */
public class Db {

    private final HikariDataSource ds;

    public Db(String jdbcUrl, String user, String pass, int poolSize) {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(jdbcUrl);
        c.setUsername(user);
        c.setPassword(pass);
        c.setMaximumPoolSize(poolSize);
        c.setMinimumIdle(poolSize);
        // 慢 SQL 场景下，连接从建立到归还的耗时就是接口耗时，连接超时要小于网关超时
        c.setConnectionTimeout(5_000);
        c.setPoolName("volc-mysql");
        c.setAutoCommit(true);
        c.addDataSourceProperty("cachePrepStmts", "true");
        c.addDataSourceProperty("prepStmtCacheSize", "250");
        this.ds = new HikariDataSource(c);
    }

    public Connection conn() throws java.sql.SQLException {
        return ds.getConnection();
    }

    /** 压测中动态改连接池上限，用来对比 pool=10 / 32 / 64 的表现 */
    public void resize(int size) {
        ds.setMaximumPoolSize(size);
        ds.setMinimumIdle(size);
    }

    public Map<String, Object> poolStat() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("poolSize", ds.getMaximumPoolSize());
        m.put("active", ds.getHikariPoolMXBean().getActiveConnections());
        m.put("idle", ds.getHikariPoolMXBean().getIdleConnections());
        m.put("waiting", ds.getHikariPoolMXBean().getThreadsAwaitingConnection());
        m.put("total", ds.getHikariPoolMXBean().getTotalConnections());
        return m;
    }

    /** 从 MySQL 全局状态里取扫描行数等指标，用来量化"到底扫了多少行" */
    public Map<String, Long> serverStat() {
        Map<String, Long> out = new LinkedHashMap<>();
        String sql = "SHOW GLOBAL STATUS WHERE Variable_name IN "
                + "('Handler_read_rnd_next','Handler_read_next','Handler_read_key',"
                + "'Select_scan','Select_full_join','Sort_merge_passes','Sort_rows',"
                + "'Created_tmp_disk_tables','Slow_queries','Threads_running')";
        try (Connection c = conn(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.put(rs.getString(1).toLowerCase(), Long.parseLong(rs.getString(2)));
            }
        } catch (Exception e) {
            out.put("error", -1L);
        }
        return out;
    }

    public void close() {
        ds.close();
    }
}
