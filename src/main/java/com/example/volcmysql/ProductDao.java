package com.example.volcmysql;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 商品列表 DAO。这里是全文的主角：
 * 同一条 SQL、同一个接口，只因为索引结构不同，耗时差 3 个数量级。
 *
 * 线上表结构：product 400 万行，没有一个二级索引（历史遗留 + 怕影响写入）。
 */
public class ProductDao {

    private final Db db;

    public ProductDao(Db db) {
        this.db = db;
    }

    // ---------- 主查询：商品列表页（前端默认按上架时间倒序，每页 20） ----------

    public static final String LIST_SQL =
            "SELECT id, title, price, stock, seller_id, created_at " +
            "FROM product WHERE status = 1 AND city = ? " +
            "ORDER BY created_at DESC LIMIT ? OFFSET ?";

    /** 页面主查询：offset 分页。lib 里的 deep 分页也用这条，只是 offset 很大 */
    public List<Product> list(String city, int size, int offset) throws SQLException {
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement(LIST_SQL)) {
            ps.setString(1, city);
            ps.setInt(2, size);
            ps.setInt(3, offset);
            ps.setFetchSize(size);
            return read(ps);
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    // ---------- 深分页：延迟关联 ----------

    public static final String DEEP_LATE_JOIN_SQL =
            "SELECT p.id, p.title, p.price, p.stock, p.seller_id, p.created_at " +
            "FROM product p JOIN (" +
            "  SELECT id FROM product WHERE status = 1 AND city = ? " +
            "  ORDER BY created_at DESC LIMIT ? OFFSET ?" +
            ") t ON p.id = t.id ORDER BY p.created_at DESC";

    public List<Product> deepLateJoin(String city, int size, int offset) throws SQLException {
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement(DEEP_LATE_JOIN_SQL)) {
            ps.setString(1, city);
            ps.setInt(2, size);
            ps.setInt(3, offset);
            return read(ps);
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    // ---------- 深分页：游标（seek method） ----------

    public static final String CURSOR_SQL =
            "SELECT id, title, price, stock, seller_id, created_at " +
            "FROM product WHERE status = 1 AND city = ? AND created_at < ? " +
            "ORDER BY created_at DESC LIMIT ?";

    public List<Product> cursor(String city, String before, int size) throws SQLException {
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement(CURSOR_SQL)) {
            ps.setString(1, city);
            ps.setString(2, before);
            ps.setInt(3, size);
            return read(ps);
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    /** 取第 offset 条的时间戳，给游标分页当"上一页最后一条" */
    public String cursorAnchor(String city, int offset) throws SQLException {
        String sql = "SELECT created_at FROM product WHERE status = 1 AND city = ? " +
                "ORDER BY created_at DESC LIMIT 1 OFFSET ?";
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, city);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "2100-01-01 00:00:00";
            }
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    // ---------- 索引失效的三种写法（生产里都真实出现过） ----------

    /** 坑 1：city_code 是 varchar，传 int —— 列被隐式转成数字，索引整体失效 */
    public static final String CAST_NUM_SQL = "SELECT id FROM product WHERE city_code = ? LIMIT 20";
    public static final String CAST_STR_SQL = "SELECT id FROM product WHERE city_code = ? LIMIT 20";

    public int castQuery(String value, boolean asNumber) throws SQLException {
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement(asNumber ? CAST_NUM_SQL : CAST_STR_SQL)) {
            if (asNumber) {
                ps.setInt(1, Integer.parseInt(value));
            } else {
                ps.setString(1, value);
            }
            int n = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    n++;
                }
            }
            return n;
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    /** 坑 2：字段上套函数 —— 索引列必须先算完函数才比较 */
    public static final String FUNC_SQL =
            "SELECT id, price FROM product WHERE DATE(created_at) = ? ORDER BY created_at DESC LIMIT 20";
    public static final String FUNC_RANGE_SQL =
            "SELECT id, price FROM product WHERE created_at >= ? AND created_at < ? " +
            "ORDER BY created_at DESC LIMIT 20";

    public int funcQuery(String day, boolean wrapped) throws SQLException {
        try (Connection c = db.conn();
             PreparedStatement ps = c.prepareStatement(wrapped ? FUNC_SQL : FUNC_RANGE_SQL)) {
            ps.setString(1, day);
            if (!wrapped) {
                ps.setString(2, day + " 23:59:59");
            }
            int n = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    n++;
                }
            }
            return n;
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    /** 坑 3：联合索引 (status, city, created_at) 只带后置列 city，前导列 status 缺失 */
    public static final String NO_PREFIX_SQL = "SELECT COUNT(*) FROM product WHERE city = ?";

    public long noPrefixCount(String city) throws SQLException {
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement(NO_PREFIX_SQL)) {
            ps.setString(1, city);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    // ---------- 总数：精确 count vs 封顶 count ----------

    public static final String COUNT_SQL = "SELECT COUNT(*) FROM product WHERE status = 1 AND city = ?";
    public static final String COUNT_CAPPED_SQL =
            "SELECT COUNT(*) FROM (SELECT 1 FROM product WHERE status = 1 AND city = ? LIMIT 10001) t";

    public long count(String city, boolean capped) throws SQLException {
        try (Connection c = db.conn();
             PreparedStatement ps = c.prepareStatement(capped ? COUNT_CAPPED_SQL : COUNT_SQL)) {
            ps.setString(1, city);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    // ---------- EXPLAIN：写文章时把执行计划抓下来 ----------

    public String explain(String sql) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement("EXPLAIN " + sql);
             ResultSet rs = ps.executeQuery()) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                for (int i = 1; i <= cols; i++) {
                    sb.append(rs.getMetaData().getColumnLabel(i)).append('=').append(rs.getString(i)).append("  ");
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private List<Product> read(PreparedStatement ps) throws SQLException {
        List<Product> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Product p = new Product();
                p.id = rs.getLong("id");
                p.title = rs.getString("title");
                p.price = rs.getBigDecimal("price");
                p.stock = rs.getInt("stock");
                p.sellerId = rs.getLong("seller_id");
                p.createdAt = rs.getString("created_at");
                list.add(p);
            }
        }
        return list;
    }

    public static class Product {
        public long id;
        public String title;
        public BigDecimal price;
        public int stock;
        public long sellerId;
        public String createdAt;
    }
}
