package com.ryuqq.lab.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prepared Statement Caching 실험.
 *
 * 비교:
 *   A. 캐시 없음: useServerPrepStmts=false, cachePrepStmts=false (기본값)
 *   B. 캐시 있음: useServerPrepStmts=true, cachePrepStmts=true, prepStmtCacheSize=250
 *
 * 같은 SQL 10,000번 실행 시간 비교.
 * 캐시가 켜져 있으면 MySQL이 파싱/최적화를 1번만 함 → 빠름.
 */
@RestController
@RequestMapping("/mysql/prepstmt")
@Profile("docker")
@ConditionalOnBean(name = "bulkInsertOn")  // docker 프로필에서만
public class PreparedStmtCacheController {

    private static final String BASE_URL =
            "jdbc:mysql://mysql:3306/labdb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul";

    private HikariDataSource noCacheDs;
    private HikariDataSource cachedDs;

    @PostConstruct
    public void init() {
        this.noCacheDs = buildDs("NoCache", BASE_URL);

        String cachedUrl = BASE_URL
                + "&useServerPrepStmts=true"
                + "&cachePrepStmts=true"
                + "&prepStmtCacheSize=250"
                + "&prepStmtCacheSqlLimit=2048";
        this.cachedDs = buildDs("Cached", cachedUrl);

        // 테이블 준비 (id=1이 존재하도록)
        try (Connection c = noCacheDs.getConnection();
             PreparedStatement p = c.prepareStatement(
                     "INSERT IGNORE INTO bulk_test (id, data) VALUES (1, 'seed')")) {
            p.executeUpdate();
        } catch (SQLException ignored) {}
    }

    @PreDestroy
    public void cleanup() {
        if (noCacheDs != null) noCacheDs.close();
        if (cachedDs != null) cachedDs.close();
    }

    private HikariDataSource buildDs(String name, String url) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName(name + "Pool");
        cfg.setJdbcUrl(url);
        cfg.setUsername("lab");
        cfg.setPassword("lab1234");
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(1);
        return new HikariDataSource(cfg);
    }

    /**
     * 같은 SELECT를 iterations번 실행.
     *
     * 핵심 포인트:
     *   양쪽 모두 conn.prepareStatement() 호출을 "매번" 함.
     *   일반적으로 실제 코드는 루프 밖에서 한 번만 prepareStatement 하지만,
     *   실제 Spring Data JPA / MyBatis 같은 프레임워크는
     *   매 메서드 호출마다 prepareStatement를 호출함.
     *   → 캐시 없으면 매번 MySQL에 PREPARE 요청
     *   → 캐시 있으면 JDBC 드라이버가 재사용
     */
    @PostMapping("/benchmark")
    public Map<String, Object> benchmark(@RequestParam(defaultValue = "10000") int iterations) throws SQLException {
        long noCacheMs = runWorkload(noCacheDs, iterations);
        long cachedMs = runWorkload(cachedDs, iterations);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("iterations", iterations);

        Map<String, Object> noCache = new LinkedHashMap<>();
        noCache.put("elapsedMs", noCacheMs);
        noCache.put("queriesPerSec", (long) (iterations / (noCacheMs / 1000.0)));
        noCache.put("설명", "매 쿼리마다 MySQL이 파싱+실행계획 수립 → CPU 낭비");

        Map<String, Object> cached = new LinkedHashMap<>();
        cached.put("elapsedMs", cachedMs);
        cached.put("queriesPerSec", (long) (iterations / (cachedMs / 1000.0)));
        cached.put("설명", "JDBC 드라이버가 PreparedStatement 재사용 + MySQL 서버 prepared stmt 재사용");

        result.put("noCache", noCache);
        result.put("cached", cached);
        result.put("speedup", String.format("%.1f배 빠름", (double) noCacheMs / cachedMs));
        return result;
    }

    /**
     * PREPARE 횟수를 MySQL의 Com_stmt_prepare 카운터로 확인.
     */
    @GetMapping("/server-stats")
    public Map<String, Object> serverStats() throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();

        try (Connection c = noCacheDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SHOW GLOBAL STATUS WHERE Variable_name IN " +
                     "('Com_stmt_prepare', 'Com_stmt_execute', 'Com_stmt_close', " +
                     "'Prepared_stmt_count', 'Queries')");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getString(2));
            }
        }

        result.put("설명", Map.of(
                "Com_stmt_prepare", "PREPARE 문 실행 횟수 (캐시 없으면 계속 증가)",
                "Com_stmt_execute", "EXECUTE 문 실행 횟수 (캐시 있으면 이게 주로 증가)",
                "Prepared_stmt_count", "현재 서버에 살아있는 prepared statement 수"
        ));
        return result;
    }

    private long runWorkload(DataSource ds, int iterations) throws SQLException {
        long start = System.currentTimeMillis();
        try (Connection conn = ds.getConnection()) {
            for (int i = 0; i < iterations; i++) {
                // 핵심: 루프 안에서 prepareStatement 호출
                // → 캐시 없으면 매번 PREPARE
                // → 캐시 있으면 JDBC가 기존 PreparedStatement 재사용
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, data FROM bulk_test WHERE id = ?")) {
                    ps.setLong(1, 1);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                    }
                }
            }
        }
        return System.currentTimeMillis() - start;
    }
}
