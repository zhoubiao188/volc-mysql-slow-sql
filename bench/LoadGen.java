import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 零依赖压测器（和前面几篇同一个工具，保持口径一致）。
 *
 *   java bench/LoadGen.java --path="/api/products?city=杭州" --threads=20 --seconds=10
 *
 * QPS = 完成请求数 / 实际墙钟时间；P50/P95/P99 来自客户端真实往返耗时，
 * 不含任何服务端自报数字 —— 少一点自欺欺人。
 */
public class LoadGen {

    public static void main(String[] args) throws Exception {
        String host = "http://127.0.0.1:18100";
        String path = "/api/products?city=" + java.net.URLEncoder.encode("杭州", StandardCharsets.UTF_8);
        int threads = 10;
        int seconds = 10;
        int timeoutMs = 60000;

        for (String a : args) {
            int i = a.indexOf('=');
            if (i < 0) {
                continue;
            }
            String k = a.substring(0, i);
            String v = a.substring(i + 1);
            switch (k) {
                case "--host" -> host = v;
                case "--path" -> path = v;
                case "--threads" -> threads = Integer.parseInt(v);
                case "--seconds" -> seconds = Integer.parseInt(v);
                case "--timeout" -> timeoutMs = Integer.parseInt(v);
                default -> {
                }
            }
        }

        List<Long> lat = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger();
        AtomicLong count = new AtomicLong();
        final String fhost = host;
        final String fpath = path;
        final int ftimeout = timeoutMs;
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        long wall0 = System.nanoTime();

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    while (System.currentTimeMillis() < deadline) {
                        call(fhost, fpath, lat, errors, count, ftimeout);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "lg-" + t).start();
        }

        start.countDown();
        done.await();
        double wall = (System.nanoTime() - wall0) / 1e9;

        List<Long> sorted = new ArrayList<>(lat);
        Collections.sort(sorted);
        System.out.printf("threads=%-3d path=%-42s n=%-6d qps=%-8.1f p50=%-6dms p95=%-6dms p99=%-6dms max=%-7dms err=%d%n",
                threads, shorten(path), count.get(), count.get() / wall,
                pct(sorted, 50), pct(sorted, 95), pct(sorted, 99),
                sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1), errors.get());
    }

    private static String shorten(String s) {
        String d = java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        return d.length() > 42 ? d.substring(0, 42) : d;
    }

    private static void call(String host, String path, List<Long> lat, AtomicInteger errors,
                             AtomicLong count, int timeoutMs) {
        long t0 = System.nanoTime();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(host + path).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    code >= 400 ? c.getErrorStream() : c.getInputStream(), StandardCharsets.UTF_8))) {
                while (r.readLine() != null) {
                    // drain
                }
            }
            if (code >= 500) {
                errors.incrementAndGet();
            }
            count.incrementAndGet();
        } catch (Exception e) {
            errors.incrementAndGet();
        } finally {
            if (c != null) {
                c.disconnect();
            }
            lat.add((System.nanoTime() - t0) / 1_000_000);
        }
    }

    private static long pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }
}
