package com.ryuqq.lab.mysql;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Slow Query Log 실험.
 *
 * 느린 쿼리 패턴 재현:
 *   1. SLEEP(N)으로 인위적 지연
 *   2. 풀스캔 (인덱스 없는 컬럼 조회)
 *   3. N+1 쿼리 시뮬레이션
 *   4. 대형 JOIN
 *
 * Slow Query Log 확인:
 *   docker compose exec mysql cat /var/lib/mysql/*-slow.log
 *
 * 켜기:
 *   docker compose exec mysql mysql -uroot -plab1234 \
 *     -e "SET GLOBAL slow_query_log = 'ON'; SET GLOBAL long_query_time = 0.1;"
 */
@RestController
@RequestMapping("/mysql/slow")
@Profile("docker")
public class SlowQueryController {

    private final JdbcTemplate jdbc;

    public SlowQueryController(DataSource ds) {
        this.jdbc = new JdbcTemplate(ds);
    }

    // ── Slow Query Log 설정 확인 ──

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> vars = jdbc.queryForList(
                "SHOW GLOBAL VARIABLES WHERE Variable_name IN " +
                "('slow_query_log', 'long_query_time', 'slow_query_log_file', " +
                " 'log_queries_not_using_indexes', 'min_examined_row_limit')");

        Map<String, Object> config = new LinkedHashMap<>();
        for (Map<String, Object> row : vars) {
            config.put((String) row.get("Variable_name"), row.get("Value"));
        }
        result.put("config", config);

        result.put("켜는 법", "docker compose exec mysql mysql -uroot -plab1234 -e " +
                "\"SET GLOBAL slow_query_log='ON'; SET GLOBAL long_query_time=0.1; " +
                "SET GLOBAL log_queries_not_using_indexes='ON';\"");

        result.put("로그 보기", "docker compose exec mysql tail -30 /var/lib/mysql/*-slow.log");
        return result;
    }

    // ── 느린 쿼리 패턴들 ──

    /**
     * 1. 인위적 지연 (SLEEP)
     * 가장 단순한 케이스 - long_query_time 초과 → 로그에 기록
     */
    @PostMapping("/sleep")
    public Map<String, Object> sleepQuery(@RequestParam(defaultValue = "1.0") double seconds) {
        long start = System.currentTimeMillis();
        jdbc.execute("SELECT SLEEP(" + seconds + ")");
        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sleepSeconds", seconds);
        result.put("elapsedMs", elapsed);
        result.put("설명", "SLEEP(" + seconds + ")으로 강제 지연. long_query_time 초과하면 slow log 기록.");
        return result;
    }

    /**
     * 2. 풀스캔 (인덱스 없는 컬럼 조회)
     * 테이블 전체를 훑어야 해서 데이터 많으면 느림
     * log_queries_not_using_indexes=ON이면 빠르게 끝나도 기록됨
     */
    @PostMapping("/full-scan")
    public Map<String, Object> fullScan(@RequestParam(defaultValue = "no-such-data") String pattern) {
        long start = System.currentTimeMillis();
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM big_table WHERE data LIKE ?",
                Long.class, "%" + pattern + "%");
        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matchCount", count);
        result.put("elapsedMs", elapsed);
        result.put("설명", "data 컬럼에 인덱스 없음 → 50만 행 풀스캔. " +
                "log_queries_not_using_indexes가 켜져 있으면 시간 짧아도 기록됨.");
        return result;
    }

    /**
     * 3. N+1 쿼리 시뮬레이션
     * Spring Data JPA에서 lazy loading으로 자주 발생하는 패턴
     */
    @PostMapping("/n-plus-one")
    public Map<String, Object> nPlusOne(@RequestParam(defaultValue = "20") int n) {
        long start = System.currentTimeMillis();

        // 1번 쿼리: 전체 목록 (ID만)
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM big_table LIMIT ?", Long.class, n);

        // N번 쿼리: 각 ID별 상세 조회 (이게 N+1)
        int detailQueries = 0;
        for (Long id : ids) {
            jdbc.queryForList("SELECT data FROM big_table WHERE id = ?", String.class, id);
            detailQueries++;
        }

        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("listQuery", 1);
        result.put("detailQueries", detailQueries);
        result.put("totalQueries", 1 + detailQueries);
        result.put("elapsedMs", elapsed);
        result.put("설명", "1 + " + n + " = " + (1 + n) + "번의 쿼리 실행. " +
                "실무 JPA에선 lazy loading으로 이렇게 됨. " +
                "해결: fetch join, @BatchSize, 또는 IN 쿼리로 한 방에.");
        result.put("개선", "IN 쿼리 1번: SELECT data FROM big_table WHERE id IN (?, ?, ...)");
        return result;
    }

    /**
     * 4. 개선된 버전 (IN 쿼리)
     */
    @PostMapping("/batch-fetch")
    public Map<String, Object> batchFetch(@RequestParam(defaultValue = "20") int n) {
        long start = System.currentTimeMillis();

        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM big_table LIMIT ?", Long.class, n);

        // IN 쿼리 1번으로 처리
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<String> data = jdbc.queryForList(
                "SELECT data FROM big_table WHERE id IN (" + placeholders + ")",
                String.class, ids.toArray());

        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalQueries", 2);
        result.put("elapsedMs", elapsed);
        result.put("rowsReturned", data.size());
        result.put("설명", "1 + N 대신 1 + 1 = 2번의 쿼리. N+1의 해결책.");
        return result;
    }

    /**
     * 5. 비효율 쿼리: OR 대신 UNION, 함수 적용된 WHERE 등
     */
    @PostMapping("/bad-patterns")
    public Map<String, Object> badPatterns() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 함수 적용 WHERE - 인덱스 무력화
        long start1 = System.currentTimeMillis();
        jdbc.queryForList("SELECT COUNT(*) FROM big_table WHERE UPPER(data) LIKE '%ABC%'", Long.class);
        long bad1 = System.currentTimeMillis() - start1;

        // 선행 와일드카드
        long start2 = System.currentTimeMillis();
        jdbc.queryForList("SELECT COUNT(*) FROM big_table WHERE data LIKE '%abc'", Long.class);
        long bad2 = System.currentTimeMillis() - start2;

        result.put("UPPER(data) LIKE", bad1 + "ms (함수 적용으로 인덱스 무력화)");
        result.put("LIKE '%xxx'", bad2 + "ms (선행 와일드카드로 인덱스 무력화)");
        result.put("교훈", "WHERE 컬럼에 함수 적용 금지. LIKE 패턴은 'xxx%' 형태로. " +
                "필요하면 Full-Text Index 또는 Elasticsearch.");
        return result;
    }
}
