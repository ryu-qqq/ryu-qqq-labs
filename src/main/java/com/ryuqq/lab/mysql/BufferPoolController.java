package com.ryuqq.lab.mysql;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * InnoDB Buffer Pool Hit Rate 관찰 실험.
 *
 * Buffer Pool: MySQL이 테이블 데이터/인덱스를 캐싱하는 메모리 영역.
 * innodb_buffer_pool_size 파라미터로 크기 조절 (기본 128MB).
 *
 * 핵심 메트릭:
 *   Innodb_buffer_pool_read_requests: Buffer Pool에서 읽으려 한 총 횟수 (logical reads)
 *   Innodb_buffer_pool_reads:          실제 디스크까지 간 횟수 (physical reads, miss)
 *   Hit Rate = 1 - (reads / read_requests)
 *
 * 실험 흐름:
 *   1. /setup: 큰 테이블 + 인덱스 생성 (디스크에 데이터 기록)
 *   2. /reset-cache: Buffer Pool 비우기 (MySQL 재시작으로 흉내)
 *   3. /full-scan: 큰 SELECT → 첫 실행은 miss 많음 (느림)
 *   4. /full-scan: 같은 쿼리 다시 → hit 많음 (빠름)
 *   5. /status: Hit Rate 확인
 */
@RestController
@RequestMapping("/mysql/buffer")
@Profile("docker")
public class BufferPoolController {

    private final JdbcTemplate jdbc;

    public BufferPoolController(DataSource ds) {
        this.jdbc = new JdbcTemplate(ds);
    }

    @PostConstruct
    public void ensureTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS big_table (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                tenant_id INT NOT NULL,
                data VARCHAR(500) NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_tenant (tenant_id)
            )
            """);
    }

    // ── 상태 조회 ──

    /**
     * 현재 Buffer Pool 상태 + Hit Rate.
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Buffer Pool 크기
        result.put("bufferPoolSizeMB", getBufferPoolSizeMB());

        // Buffer Pool 사용 현황
        long totalPages = getStatusLong("Innodb_buffer_pool_pages_total");
        long freePages = getStatusLong("Innodb_buffer_pool_pages_free");
        long dataPages = getStatusLong("Innodb_buffer_pool_pages_data");
        long dirtyPages = getStatusLong("Innodb_buffer_pool_pages_dirty");

        Map<String, Object> pages = new LinkedHashMap<>();
        pages.put("total", totalPages);
        pages.put("data (캐시된 데이터)", dataPages);
        pages.put("free (빈 슬롯)", freePages);
        pages.put("dirty (수정됐지만 디스크 flush 전)", dirtyPages);
        pages.put("usagePercent", totalPages > 0
                ? String.format("%.1f%%", (double) dataPages / totalPages * 100)
                : "N/A");
        result.put("pages", pages);

        // Hit Rate (누적치)
        long readRequests = getStatusLong("Innodb_buffer_pool_read_requests");
        long reads = getStatusLong("Innodb_buffer_pool_reads");
        double hitRate = readRequests > 0 ? 1.0 - ((double) reads / readRequests) : 1.0;

        Map<String, Object> hr = new LinkedHashMap<>();
        hr.put("totalReadRequests (logical)", readRequests);
        hr.put("diskReads (physical)", reads);
        hr.put("hitRate", String.format("%.2f%%", hitRate * 100));
        hr.put("평가", hitRate > 0.99 ? "✅ 매우 좋음 (99%+)"
                : hitRate > 0.95 ? "⚠️ 보통 (95~99%)"
                : "❌ 나쁨 (95% 이하, 메모리 부족 의심)");
        result.put("hitRate", hr);

        return result;
    }

    // ── 실험 셋업 ──

    /**
     * 큰 테이블 채우기. Buffer Pool보다 크게 만들어서 miss를 유도.
     * 예: rows=500000 (약 250MB 데이터)
     */
    @PostMapping("/setup")
    public Map<String, Object> setup(@RequestParam(defaultValue = "500000") int rows) {
        jdbc.execute("TRUNCATE TABLE big_table");

        // 배치 INSERT로 빠르게 채움
        int batchSize = 1000;
        int batches = rows / batchSize;
        long start = System.currentTimeMillis();

        StringBuilder values = new StringBuilder();
        for (int b = 0; b < batches; b++) {
            values.setLength(0);
            for (int i = 0; i < batchSize; i++) {
                if (i > 0) values.append(",");
                int tenantId = (b * batchSize + i) % 100;  // tenant 100개
                String data = "data-" + (b * batchSize + i) + "-padding-".repeat(20);
                values.append("(").append(tenantId).append(",'").append(data).append("')");
            }
            jdbc.execute("INSERT INTO big_table (tenant_id, data) VALUES " + values);
        }

        long elapsed = System.currentTimeMillis() - start;
        long tableSizeMB = jdbc.queryForObject(
                "SELECT (DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024 FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = 'labdb' AND TABLE_NAME = 'big_table'",
                Long.class);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowsInserted", rows);
        result.put("elapsedMs", elapsed);
        result.put("tableSizeMB", tableSizeMB);
        result.put("bufferPoolSizeMB", getBufferPoolSizeMB());
        result.put("설명", "테이블 크기가 Buffer Pool보다 크면 캐시에 다 못 들어감 → miss 발생. " +
                "다음 /full-scan 호출 시 첫 번째는 느리고 두 번째는 빠름.");
        return result;
    }

    /**
     * Buffer Pool을 비워서 "콜드 스타트" 상태 만들기.
     * MySQL 재시작 대신 DROP + 재생성으로 캐시 무효화.
     */
    @PostMapping("/reset-cache")
    public Map<String, Object> resetCache() {
        // MySQL은 FLUSH TABLES로 buffer pool을 직접 비울 수 있는 정식 명령이 없음
        // 대신 buffer pool dump 비활성화 + 큰 쿼리로 기존 캐시 밀어내기 방식 사용
        // 또는 MySQL 재시작이 가장 확실함
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("방법", "Buffer Pool을 강제로 비우는 정식 명령은 없음");
        result.put("대안1", "docker compose restart mysql (완전 재시작)");
        result.put("대안2", "다른 큰 테이블 스캔해서 기존 캐시 밀어내기");
        result.put("현재상태", status());
        return result;
    }

    // ── 실험 쿼리 ──

    /**
     * 풀 스캔 쿼리 실행 → 모든 행을 Buffer Pool에 로드.
     * 첫 실행: 디스크에서 읽음 (miss 많음, 느림)
     * 두 번째 실행: 캐시 히트 (빠름)
     */
    @PostMapping("/full-scan")
    public Map<String, Object> fullScan() {
        long readsBefore = getStatusLong("Innodb_buffer_pool_reads");
        long requestsBefore = getStatusLong("Innodb_buffer_pool_read_requests");

        long start = System.currentTimeMillis();
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM big_table", Long.class);
        long elapsed = System.currentTimeMillis() - start;

        long readsAfter = getStatusLong("Innodb_buffer_pool_reads");
        long requestsAfter = getStatusLong("Innodb_buffer_pool_read_requests");

        long diskReads = readsAfter - readsBefore;
        long totalRequests = requestsAfter - requestsBefore;
        double hitRate = totalRequests > 0 ? 1.0 - ((double) diskReads / totalRequests) : 1.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowsScanned", count);
        result.put("elapsedMs", elapsed);

        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("totalRequests (이번 쿼리에서)", totalRequests);
        delta.put("diskReads (이번 쿼리에서 miss)", diskReads);
        delta.put("hitRate", String.format("%.2f%%", hitRate * 100));
        delta.put("평가", diskReads == 0 ? "✅ 전부 캐시 히트 (뜨거운 쿼리)"
                : diskReads > totalRequests * 0.5 ? "❌ 대부분 디스크 읽음 (콜드)"
                : "⚠️ 일부 디스크 읽음");
        result.put("delta", delta);

        return result;
    }

    /**
     * 랜덤 row 조회 (인덱스로).
     * 자주 쓰는 쿼리 패턴 (API 요청 흉내).
     */
    @PostMapping("/random-read")
    public Map<String, Object> randomRead(@RequestParam(defaultValue = "1000") int queries) {
        long readsBefore = getStatusLong("Innodb_buffer_pool_reads");
        long requestsBefore = getStatusLong("Innodb_buffer_pool_read_requests");

        long start = System.currentTimeMillis();
        java.util.Random r = new java.util.Random();
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM big_table", Long.class);
        for (int i = 0; i < queries; i++) {
            long id = 1 + r.nextInt(total.intValue());
            try {
                jdbc.queryForObject("SELECT data FROM big_table WHERE id = ?", String.class, id);
            } catch (Exception ignored) {}
        }
        long elapsed = System.currentTimeMillis() - start;

        long diskReads = getStatusLong("Innodb_buffer_pool_reads") - readsBefore;
        long totalRequests = getStatusLong("Innodb_buffer_pool_read_requests") - requestsBefore;
        double hitRate = totalRequests > 0 ? 1.0 - ((double) diskReads / totalRequests) : 1.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queries", queries);
        result.put("elapsedMs", elapsed);
        result.put("queriesPerSec", (long) (queries / (elapsed / 1000.0)));
        result.put("totalRequests", totalRequests);
        result.put("diskReads", diskReads);
        result.put("hitRate", String.format("%.2f%%", hitRate * 100));
        return result;
    }

    // ── 유틸 ──

    private long getStatusLong(String variableName) {
        Long value = jdbc.queryForObject(
                "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = ?",
                Long.class, variableName);
        return value != null ? value : 0;
    }

    private long getBufferPoolSizeMB() {
        Long bytes = jdbc.queryForObject(
                "SELECT @@global.innodb_buffer_pool_size", Long.class);
        return bytes != null ? bytes / 1024 / 1024 : 0;
    }
}
