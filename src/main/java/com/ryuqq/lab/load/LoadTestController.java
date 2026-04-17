package com.ryuqq.lab.load;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.*;

/**
 * 풀 상태를 직접 바꿔보면서 실험하는 컨트롤러.
 *
 * 실험 1: DB 커넥션 고갈 시키기
 * 실험 2: 스레드 풀 꽉 채우기
 * 실험 3: Direct Buffer 직접 할당해보기
 * 실험 4: Redis 동시 요청 폭탄
 * 실험 5: 힙 메모리 채워서 GC 관찰하기
 */
@RestController
@RequestMapping("/load")
@ConditionalOnBean(JdbcTemplate.class)
public class LoadTestController {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    // Direct Buffer 실험용 (해제 안 하고 들고 있기)
    private final List<ByteBuffer> directBufferHolder = new ArrayList<>();

    // 힙 메모리 실험용
    private final List<byte[]> heapHolder = new ArrayList<>();

    public LoadTestController(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
    }

    // ========================================
    // 실험 1: DB 커넥션 고갈
    // ========================================
    /**
     * DB 커넥션을 N개 빌려서 holdSeconds 동안 안 돌려줌.
     * /monitor/hikari 에서 active 올라가고 waiting 올라가는 거 관찰.
     *
     * 예: /load/db-exhaust?connections=5&holdSeconds=10
     *     → 커넥션 5개를 10초간 점유
     *     → 이 동안 /monitor/hikari 호출하면 active=5, waiting이 보임
     */
    @GetMapping("/db-exhaust")
    public Map<String, Object> dbExhaust(
            @RequestParam(defaultValue = "3") int connections,
            @RequestParam(defaultValue = "10") int holdSeconds) {

        ExecutorService executor = Executors.newFixedThreadPool(connections);
        CountDownLatch startLatch = new CountDownLatch(connections);
        CountDownLatch endLatch = new CountDownLatch(1);

        for (int i = 0; i < connections; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    // 각 스레드가 DB 커넥션을 잡고 sleep (커넥션 점유)
                    jdbcTemplate.execute("SELECT SLEEP(" + holdSeconds + ")");
                } catch (Exception e) {
                    // 커넥션 타임아웃 시 여기로
                }
            });
        }
        executor.shutdown();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", connections + "개 커넥션을 " + holdSeconds + "초간 점유 시작");
        result.put("확인방법", "다른 탭에서 /monitor/hikari 호출 → active=" + connections + " 확인");
        result.put("추가실험", "holdSeconds 동안 /load/db-query 호출 → waiting_threads 올라가는 거 관찰");
        return result;
    }

    /**
     * 단순 DB 쿼리. 커넥션 고갈 중에 호출하면 대기하게 됨.
     */
    @GetMapping("/db-query")
    public Map<String, Object> dbQuery() {
        long start = System.currentTimeMillis();
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", result);
        response.put("elapsed_ms", elapsed);
        response.put("설명", elapsed > 100
                ? "⚠️ " + elapsed + "ms 걸림 → 커넥션 대기 발생! (풀 부족)"
                : "✅ 정상 (" + elapsed + "ms)");
        return response;
    }

    // ========================================
    // 실험 2: Tomcat 스레드 고갈
    // ========================================
    /**
     * Tomcat 스레드를 점유해서 풀 고갈 시키기.
     * 여러 탭/터미널에서 동시에 호출하면 스레드 풀이 찬다.
     *
     * 예: curl "localhost:8080/load/thread-block?seconds=30" 를 20번 동시 실행
     *     → Tomcat 스레드 20개 점유
     *     → 이 동안 다른 API 호출이 느려지거나 타임아웃
     */
    @GetMapping("/thread-block")
    public Map<String, Object> threadBlock(@RequestParam(defaultValue = "10") int seconds) {
        String threadName = Thread.currentThread().getName();
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("thread", threadName);
        result.put("blocked_seconds", seconds);
        result.put("message", "이 스레드를 " + seconds + "초간 점유했음");
        result.put("확인방법", "/monitor/threads 에서 tomcat_worker_threads 변화 관찰");
        return result;
    }

    // ========================================
    // 실험 3: Direct Buffer 할당
    // ========================================
    /**
     * Direct Buffer를 직접 할당해서 Non-Heap 메모리 증가 관찰.
     *
     * 예: /load/direct-buffer?sizeMB=10&count=5
     *     → 10MB × 5개 = 50MB Direct Buffer 할당
     *     → /monitor/buffers 에서 direct.used_MB 증가 확인
     */
    @GetMapping("/direct-buffer")
    public Map<String, Object> allocateDirectBuffer(
            @RequestParam(defaultValue = "10") int sizeMB,
            @RequestParam(defaultValue = "1") int count) {

        long before = getDirectBufferUsed();

        for (int i = 0; i < count; i++) {
            ByteBuffer buf = ByteBuffer.allocateDirect(sizeMB * 1024 * 1024);
            // 실제로 쓰기를 해야 OS가 물리 메모리를 할당함
            for (int j = 0; j < buf.capacity(); j += 4096) {
                buf.put(j, (byte) 1);
            }
            directBufferHolder.add(buf); // GC 안 되게 홀드
        }

        long after = getDirectBufferUsed();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allocated", sizeMB + "MB × " + count + "개 = " + (sizeMB * count) + "MB");
        result.put("direct_buffer_before_MB", before / 1024 / 1024);
        result.put("direct_buffer_after_MB", after / 1024 / 1024);
        result.put("total_held_buffers", directBufferHolder.size());
        result.put("확인방법", "/monitor/buffers 에서 direct.used_MB 확인");
        result.put("정리", "/load/direct-buffer-clear 호출로 해제");
        return result;
    }

    /**
     * 할당한 Direct Buffer 전부 해제.
     */
    @GetMapping("/direct-buffer-clear")
    public Map<String, Object> clearDirectBuffer() {
        int count = directBufferHolder.size();
        directBufferHolder.clear();
        // Direct Buffer는 GC Cleaner가 해제하므로 즉시 줄지 않을 수 있음
        System.gc(); // GC 힌트 (보장은 아님)

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cleared", count + "개 버퍼 참조 해제");
        result.put("주의", "Direct Buffer는 GC Cleaner가 해제하므로 /monitor/buffers 에서 즉시 줄지 않을 수 있음");
        return result;
    }

    // ========================================
    // 실험 4: Redis 동시 요청
    // ========================================
    /**
     * Redis에 동시 요청을 보내서 Netty 버퍼 사용량 관찰.
     *
     * 예: /load/redis-flood?count=1000
     *     → Redis SET 1000번 동시 실행
     *     → /monitor/netty 에서 used_direct_memory 변화 관찰
     */
    @GetMapping("/redis-flood")
    public Map<String, Object> redisFlood(@RequestParam(defaultValue = "100") int count) {
        ExecutorService executor = Executors.newFixedThreadPool(20);
        long start = System.currentTimeMillis();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                redisTemplate.opsForValue().set("lab:key:" + idx, "value-" + idx);
                redisTemplate.opsForValue().get("lab:key:" + idx);
            }));
        }

        // 완료 대기
        for (Future<?> f : futures) {
            try { f.get(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        executor.shutdown();

        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requests", count + " SET + GET 완료");
        result.put("elapsed_ms", elapsed);
        result.put("확인방법", "/monitor/netty 에서 used_direct_memory_bytes 변화 관찰");
        return result;
    }

    // ========================================
    // 실험 5: 힙 채우기 (GC 관찰)
    // ========================================
    /**
     * 힙에 데이터를 밀어넣어서 GC 동작 관찰.
     *
     * 예: /load/heap-fill?sizeMB=50&count=5
     *     → 50MB × 5개 = 250MB 힙 사용
     *     → /monitor/memory 에서 heap.used_MB 증가 + GC 발생 관찰
     */
    @GetMapping("/heap-fill")
    public Map<String, Object> heapFill(
            @RequestParam(defaultValue = "50") int sizeMB,
            @RequestParam(defaultValue = "1") int count) {

        long before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        for (int i = 0; i < count; i++) {
            heapHolder.add(new byte[sizeMB * 1024 * 1024]);
        }

        long after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allocated", sizeMB + "MB × " + count + "개 = " + (sizeMB * count) + "MB");
        result.put("heap_before_MB", before / 1024 / 1024);
        result.put("heap_after_MB", after / 1024 / 1024);
        result.put("total_held_arrays", heapHolder.size());
        result.put("정리", "/load/heap-clear 호출로 해제");
        return result;
    }

    @GetMapping("/heap-clear")
    public Map<String, Object> heapClear() {
        int count = heapHolder.size();
        heapHolder.clear();
        System.gc();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cleared", count + "개 배열 해제 → GC가 회수 예정");
        result.put("확인방법", "/monitor/memory 에서 heap.used_MB 감소 확인");
        return result;
    }

    // ========================================
    // 유틸
    // ========================================
    private long getDirectBufferUsed() {
        return ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)
                .stream()
                .filter(p -> "direct".equals(p.getName()))
                .findFirst()
                .map(java.lang.management.BufferPoolMXBean::getMemoryUsed)
                .orElse(0L);
    }
}
