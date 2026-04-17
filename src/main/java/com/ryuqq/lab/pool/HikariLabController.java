package com.ryuqq.lab.pool;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HikariCP 커넥션 풀 실험용 컨트롤러.
 *
 * 실험 시나리오:
 *   1. 커넥션 고갈 시뮬레이션 (/pool/exhaust)
 *   2. 커넥션 대기 관찰     (/pool/query-wait)
 *   3. 풀 상태 모니터링      (/pool/status)
 *   4. 스레드풀 조합 실험    (/pool/combined-stress)
 *   5. Connection Leak      (/pool/leak)
 */
@RestController
@RequestMapping("/pool")
public class HikariLabController {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    // leak 시뮬레이션용 (실제로 닫지 않음)
    private final List<Connection> leakedConnections = new CopyOnWriteArrayList<>();

    public HikariLabController(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    // ── 실험 1: 커넥션 고갈 ──

    /**
     * 커넥션 N개를 빌려서 holdSeconds 동안 점유.
     * 다른 요청이 들어오면 풀이 고갈됨을 관찰.
     */
    @PostMapping("/exhaust")
    public Map<String, Object> exhaust(
            @RequestParam(defaultValue = "5") int connections,
            @RequestParam(defaultValue = "15") int holdSeconds) {

        // 비동기로 N개 커넥션 점유
        ExecutorService executor = Executors.newFixedThreadPool(connections);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < connections; i++) {
            executor.submit(() -> {
                try {
                    // SLEEP으로 커넥션 점유 (MySQL 함수)
                    jdbcTemplate.execute("SELECT SLEEP(" + holdSeconds + ")");
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                }
            });
        }
        executor.shutdown();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", connections + "개 커넥션 점유 시작. " + holdSeconds + "초간 유지");
        result.put("poolStatus", getPoolStatus());
        result.put("다음", "다른 탭에서 POST /pool/query-wait 호출 → 대기 시간 관찰");
        return result;
    }

    /**
     * 단순 쿼리. 풀 고갈 중이면 이 요청이 대기하게 됨.
     */
    @PostMapping("/query-wait")
    public Map<String, Object> queryWait() {
        long start = System.currentTimeMillis();
        String status = "성공";
        String error = null;

        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        } catch (Exception e) {
            status = "실패";
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("elapsedMs", elapsed);
        if (error != null) result.put("error", error);
        result.put("분석", elapsed < 100
                ? "✅ 정상 (커넥션 즉시 획득)"
                : elapsed < 5000
                    ? "⚠️ " + elapsed + "ms 대기 (풀에 여유 없음, 다른 커넥션이 반납 대기)"
                    : "❌ 타임아웃 발생 가능성 (connection-timeout 확인)");
        result.put("poolStatus", getPoolStatus());
        return result;
    }

    // ── 실험 2: 풀 상태 모니터링 ──

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("config", getPoolConfig());
        result.put("current", getPoolStatus());
        result.put("설명", Map.of(
            "active", "지금 쿼리 실행 중인 커넥션 (빌려간 것)",
            "idle", "풀에서 대기 중 (반납된 것)",
            "total", "현재 풀에 존재하는 전체 커넥션 (active + idle)",
            "threadsAwaiting", "커넥션을 기다리는 스레드 수. 0보다 크면 풀 부족!"
        ));
        return result;
    }

    // ── 실험 3: 스레드풀 + 커넥션풀 조합 ──

    /**
     * 톰캣 스레드와 DB 커넥션을 동시에 사용하는 시나리오.
     * 병목이 스레드인지, 커넥션인지 관찰.
     *
     * 동시에 여러 번 호출:
     *   for i in $(seq 1 20); do curl -s 'localhost:8080/pool/combined-stress?holdMs=3000' & done
     *
     * 관찰 포인트:
     *   - Tomcat busy가 먼저 차는지? → 스레드 병목
     *   - HikariCP pending이 올라가는지? → 커넥션 병목
     */
    @GetMapping("/combined-stress")
    public Map<String, Object> combinedStress(@RequestParam(defaultValue = "3000") int holdMs) {
        long start = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();

        // 커넥션 얻고 holdMs 동안 유지
        try {
            jdbcTemplate.execute("SELECT SLEEP(" + (holdMs / 1000.0) + ")");
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("thread", threadName);
            result.put("error", e.getMessage());
            return result;
        }

        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("thread", threadName);
        result.put("elapsedMs", elapsed);
        result.put("poolStatus", getPoolStatus());
        return result;
    }

    // ── 실험 4: Connection Leak 시뮬레이션 ──

    /**
     * 커넥션을 빌려놓고 안 닫음 → 실제 운영에서 try-with-resources 안 쓴 코드 재현.
     * leakDetectionThreshold 설정 시 경고 로그 발생.
     */
    @PostMapping("/leak")
    public Map<String, Object> leak(@RequestParam(defaultValue = "1") int count) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();

        for (int i = 0; i < count; i++) {
            Connection conn = dataSource.getConnection();
            // 커넥션 빌려놓고 close() 호출 안 함 → leak
            leakedConnections.add(conn);
        }

        result.put("leaked", count + "개 커넥션 leak (close 안 함)");
        result.put("totalLeaked", leakedConnections.size());
        result.put("poolStatus", getPoolStatus());
        result.put("설명", "application.yml의 leak-detection-threshold 시간이 지나면 로그에 경고. " +
                "POST /pool/leak-fix로 원래대로 돌려놓기");
        return result;
    }

    @PostMapping("/leak-fix")
    public Map<String, Object> leakFix() {
        int count = leakedConnections.size();
        for (Connection conn : leakedConnections) {
            try { conn.close(); } catch (Exception ignored) {}
        }
        leakedConnections.clear();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("closed", count + "개 커넥션 반납");
        result.put("poolStatus", getPoolStatus());
        return result;
    }

    // ── 실험 6: 커넥션 풀 Warming ──

    /**
     * 풀 웜업: 한꺼번에 많은 커넥션을 빌려서 max까지 채운 뒤 반납.
     * 이후에는 요청이 와도 커넥션 생성 지연이 없음.
     */
    @PostMapping("/warm-up")
    public Map<String, Object> warmUp() throws SQLException {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            return Map.of("error", "HikariDataSource 아님");
        }
        int maxPool = hikari.getMaximumPoolSize();

        long start = System.currentTimeMillis();
        List<Connection> conns = new ArrayList<>();
        try {
            // max까지 전부 빌림 → 풀이 부족하면 새로 만듦
            for (int i = 0; i < maxPool; i++) {
                conns.add(dataSource.getConnection());
            }
        } finally {
            // 전부 반납
            for (Connection c : conns) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }
        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("warmedUpTo", maxPool + "개");
        result.put("elapsedMs", elapsed);
        result.put("poolStatus", getPoolStatus());
        result.put("설명", "풀을 강제로 max까지 채운 뒤 전부 반납. " +
                "이후에는 이미 만들어진 커넥션을 재사용하므로 " +
                "첫 요청이 빠름. 서버 시작 직후 이 엔드포인트를 한 번 호출하면 웜업 효과.");
        return result;
    }

    /**
     * 커넥션 얻는 데 걸린 시간 측정.
     * 콜드 풀: 새 커넥션 생성 → ~10ms
     * 웜 풀: 기존 커넥션 재사용 → <1ms
     */
    @GetMapping("/acquire-time")
    public Map<String, Object> acquireTime(@RequestParam(defaultValue = "5") int samples) throws SQLException {
        List<Long> timings = new ArrayList<>();

        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            try (Connection c = dataSource.getConnection()) {
                // 아무것도 안 함 → 순수 커넥션 획득 시간만 측정
            }
            long elapsedUs = (System.nanoTime() - start) / 1000;
            timings.add(elapsedUs);
        }

        long avg = (long) timings.stream().mapToLong(Long::longValue).average().orElse(0);
        long max = timings.stream().mapToLong(Long::longValue).max().orElse(0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("samples", samples);
        result.put("timingsUs", timings);
        result.put("avgUs", avg);
        result.put("maxUs", max);
        result.put("설명", "콜드 풀이면 첫 샘플이 오래 걸림 (커넥션 생성: TCP+인증+세션초기화 ~10ms). " +
                "웜 풀이면 전부 <1ms (단순히 풀에서 꺼내기).");
        result.put("poolStatus", getPoolStatus());
        return result;
    }

    // ── 실험 5: MySQL wait_timeout 시뮬레이션 ──

    /**
     * MySQL의 wait_timeout을 임의로 짧게 설정해서 커넥션이 끊기는 상황 재현.
     * 세션 레벨로만 설정되므로 이 커넥션이 풀에 반납된 후 재사용될 때 영향.
     */
    @PostMapping("/set-mysql-timeout")
    public Map<String, Object> setMysqlTimeout(@RequestParam(defaultValue = "10") int seconds) {
        // 현재 세션의 wait_timeout 변경
        // GLOBAL로 설정해야 모든 커넥션에 영향 (권한 필요)
        try {
            jdbcTemplate.execute("SET GLOBAL wait_timeout = " + seconds);
            jdbcTemplate.execute("SET GLOBAL interactive_timeout = " + seconds);
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getMessage());
            err.put("hint", "GLOBAL 설정은 SUPER 권한 필요. DB 사용자 권한 확인.");
            return err;
        }

        // 확인
        Integer current = jdbcTemplate.queryForObject(
                "SELECT @@global.wait_timeout", Integer.class);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("waitTimeoutSeconds", current);
        result.put("설명", "MySQL이 " + seconds + "초 이상 idle인 커넥션을 끊음. " +
                "풀 안의 idle 커넥션이 " + seconds + "초 지나면 죽음. " +
                "그 후 쿼리 날리면 Communications link failure. " +
                "복구는 /pool/set-mysql-timeout?seconds=28800");
        return result;
    }

    /**
     * 커넥션을 N초 동안 idle 상태로 둔 후 쿼리.
     * MySQL wait_timeout보다 길게 대기시키면 끊긴 커넥션을 만남.
     */
    @PostMapping("/test-stale-connection")
    public Map<String, Object> testStaleConnection(@RequestParam(defaultValue = "15") int idleSeconds) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 먼저 커넥션을 한 번 빌리고 반납 (풀에 idle로 저장됨)
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        } catch (Exception e) {
            result.put("initialQueryFailed", e.getMessage());
        }

        // idleSeconds 동안 대기
        try {
            Thread.sleep(idleSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 다시 쿼리 시도 (풀에 있던 idle 커넥션을 재사용)
        long start = System.currentTimeMillis();
        String queryResult;
        String error = null;
        try {
            Integer value = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            queryResult = "성공: " + value;
        } catch (Exception e) {
            queryResult = "실패";
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        long elapsed = System.currentTimeMillis() - start;

        result.put("idleWaitSeconds", idleSeconds);
        result.put("queryResult", queryResult);
        result.put("elapsedMs", elapsed);
        if (error != null) result.put("error", error);
        result.put("poolStatus", getPoolStatus());
        result.put("설명", "MySQL wait_timeout보다 오래 idle이면 끊긴 커넥션. " +
                "HikariCP의 keepaliveTime이 짧으면 주기적 ping으로 방어. " +
                "connection-test-query로 빌려줄 때 검증도 가능.");
        return result;
    }

    // ── 유틸 ──

    private Map<String, Object> getPoolStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        if (dataSource instanceof HikariDataSource hikari) {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            if (pool != null) {
                status.put("active", pool.getActiveConnections());
                status.put("idle", pool.getIdleConnections());
                status.put("total", pool.getTotalConnections());
                status.put("threadsAwaiting", pool.getThreadsAwaitingConnection());
            }
        }
        return status;
    }

    private Map<String, Object> getPoolConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        if (dataSource instanceof HikariDataSource hikari) {
            config.put("poolName", hikari.getPoolName());
            config.put("maximumPoolSize", hikari.getMaximumPoolSize());
            config.put("minimumIdle", hikari.getMinimumIdle());
            config.put("connectionTimeoutMs", hikari.getConnectionTimeout());
            config.put("idleTimeoutMs", hikari.getIdleTimeout());
            config.put("maxLifetimeMs", hikari.getMaxLifetime());
            config.put("leakDetectionThresholdMs", hikari.getLeakDetectionThreshold());
            config.put("keepaliveTimeMs", hikari.getKeepaliveTime());
        }
        return config;
    }
}
