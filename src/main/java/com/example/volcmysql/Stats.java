package com.example.volcmysql;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** 接口侧指标：请求数、耗时、慢请求、出错数。压测脚本按差值算 QPS / 平均耗时 */
public class Stats {

    public final LongAdder requests = new LongAdder();
    public final LongAdder errors = new LongAdder();
    public final LongAdder totalCostMs = new LongAdder();
    public final LongAdder slow = new LongAdder();      // >1000ms
    public final AtomicLong maxCostMs = new AtomicLong();
    public final LongAdder rowExamined = new LongAdder();

    public void hit(long costMs, int rows, boolean error) {
        requests.increment();
        totalCostMs.add(costMs);
        if (error) {
            errors.increment();
        }
        if (costMs > 1000) {
            slow.increment();
        }
        maxCostMs.accumulateAndGet(costMs, Math::max);
        rowExamined.add(rows);
    }

    public Map<String, Object> snapshot() {
        long n = requests.sum();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requests", n);
        m.put("errors", errors.sum());
        m.put("avgCostMs", n == 0 ? 0 : totalCostMs.sum() / n);
        m.put("maxCostMs", maxCostMs.get());
        m.put("slow1s", slow.sum());
        m.put("rowsReturned", rowExamined.sum());
        return m;
    }
}
