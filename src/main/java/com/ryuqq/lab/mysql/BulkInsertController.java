package com.ryuqq.lab.mysql;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bulk Insert 실험.
 *
 * 비교:
 *   A. rewriteBatchedStatements 없음 (기본)
 *      → JDBC가 INSERT를 한 번에 1행씩 실제로 전송
 *      → 네트워크 왕복 N번
 *
 *   B. rewriteBatchedStatements=true
 *      → JDBC가 INSERT를 multi-row로 재작성
 *      → INSERT INTO t (...) VALUES (...), (...), (...) 한 방에 전송
 *      → 네트워크 왕복 1번 (또는 몇 번으로 쪼개짐)
 *
 * 실행 전 테이블은 @PostConstruct에서 자동 생성.
 */
@RestController
@RequestMapping("/mysql/bulk")
@ConditionalOnBean(name = "bulkInsertOn")
public class BulkInsertController {

    private final JdbcTemplate off;
    private final JdbcTemplate on;

    public BulkInsertController(
            DataSource defaultDs,
            @Qualifier("bulkInsertOn") DataSource onDs) {
        this.off = new JdbcTemplate(defaultDs);   // 기본 DataSource = rewriteBatchedStatements 없음
        this.on = new JdbcTemplate(onDs);
    }

    @PostConstruct
    public void ensureTable() {
        off.execute("""
            CREATE TABLE IF NOT EXISTS bulk_test (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                data VARCHAR(255) NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """);
    }

    /**
     * 벤치마크: 두 DataSource로 각각 N건 INSERT 후 시간 비교.
     *
     * 예: POST /mysql/bulk/benchmark?rows=10000
     */
    @PostMapping("/benchmark")
    public Map<String, Object> benchmark(@RequestParam(defaultValue = "10000") int rows) throws SQLException {
        // 테스트 시작 전 테이블 비우기
        off.execute("TRUNCATE TABLE bulk_test");

        // 1) rewriteBatchedStatements=false
        long offElapsed = runBatchInsert(off, rows);
        long offRowsInDb = off.queryForObject("SELECT COUNT(*) FROM bulk_test", Long.class);
        long offQueries = readGlobalQueries();

        off.execute("TRUNCATE TABLE bulk_test");

        // 2) rewriteBatchedStatements=true
        long beforeQueries = readGlobalQueries();
        long onElapsed = runBatchInsert(on, rows);
        long onRowsInDb = on.queryForObject("SELECT COUNT(*) FROM bulk_test", Long.class);
        long onQueries = readGlobalQueries() - beforeQueries;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);

        Map<String, Object> offInfo = new LinkedHashMap<>();
        offInfo.put("elapsedMs", offElapsed);
        offInfo.put("rowsInDb", offRowsInDb);
        offInfo.put("tps", (long) (rows / (offElapsed / 1000.0)));
        offInfo.put("설명", "JDBC가 INSERT 한 번에 1행씩 실제 전송. 네트워크 왕복 N번.");

        Map<String, Object> onInfo = new LinkedHashMap<>();
        onInfo.put("elapsedMs", onElapsed);
        onInfo.put("rowsInDb", onRowsInDb);
        onInfo.put("tps", (long) (rows / (onElapsed / 1000.0)));
        onInfo.put("serverQueries", onQueries);
        onInfo.put("설명", "JDBC가 multi-row INSERT로 재작성. 네트워크 왕복 매우 적음.");

        result.put("rewriteBatchedStatements=false", offInfo);
        result.put("rewriteBatchedStatements=true", onInfo);
        result.put("speedup", String.format("%.1f배 빠름", (double) offElapsed / onElapsed));
        result.put("힌트", "MySQL general_log 켜면 실제 전송된 쿼리 확인 가능. " +
                "docker compose exec mysql mysql -uroot -plab1234 -e \"SET GLOBAL general_log = 'ON';\"");

        return result;
    }

    /**
     * "Naive" 벤치마크: addBatch 안 쓰고 그냥 execute 한 번씩.
     * 실제 네트워크 왕복 = rows 번.
     * rewriteBatchedStatements도 이 경우엔 효과 없음 (batch가 없으니까).
     *
     * 이걸 돌려보면 왜 batch가 중요한지, 그리고 왜 RDS 쓰면 더 큰 차이가 나는지 체감 가능.
     */
    @PostMapping("/naive")
    public Map<String, Object> naive(@RequestParam(defaultValue = "1000") int rows) throws SQLException {
        off.execute("TRUNCATE TABLE bulk_test");

        long start = System.currentTimeMillis();
        try (Connection conn = off.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bulk_test (data) VALUES (?)")) {
            conn.setAutoCommit(true);  // 매 INSERT가 1 트랜잭션 → 매번 디스크 fsync
            for (int i = 0; i < rows; i++) {
                ps.setString(1, "data-" + i);
                ps.executeUpdate();  // ← 바로 실행, 네트워크 왕복
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        long inDb = off.queryForObject("SELECT COUNT(*) FROM bulk_test", Long.class);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("elapsedMs", elapsed);
        result.put("rowsInDb", inDb);
        result.put("tps", (long) (rows / (elapsed / 1000.0)));
        result.put("설명", "addBatch 없이 execute 한 번씩. autoCommit=true로 각 INSERT가 개별 트랜잭션. " +
                "네트워크 왕복 rows번 + fsync rows번. 지금 /benchmark 결과와 비교해봐.");
        return result;
    }

    /**
     * 양쪽 모두 addBatch → executeBatch 패턴을 씀.
     * 코드는 동일. 차이는 오직 JDBC URL 파라미터.
     */
    private long runBatchInsert(JdbcTemplate jdbcTemplate, int rows) throws SQLException {
        DataSource ds = jdbcTemplate.getDataSource();
        if (ds == null) throw new IllegalStateException("no datasource");

        long start = System.currentTimeMillis();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bulk_test (data) VALUES (?)")) {
            conn.setAutoCommit(false);
            for (int i = 0; i < rows; i++) {
                ps.setString(1, "data-" + i);
                ps.addBatch();
                if (i % 1000 == 999) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
            conn.commit();
        }
        return System.currentTimeMillis() - start;
    }

    /**
     * 현재 세션이 MySQL에 날린 총 쿼리 카운트 (참고용).
     */
    private long readGlobalQueries() {
        return off.queryForObject(
                "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Queries'",
                Long.class);
    }
}
