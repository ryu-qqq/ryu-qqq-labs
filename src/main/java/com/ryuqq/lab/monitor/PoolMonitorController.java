package com.ryuqq.lab.monitor;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.netty.buffer.ByteBufAllocatorMetric;
import io.netty.buffer.PooledByteBufAllocator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JVM 메모리, 스레드, 커넥션, 버퍼 풀 상태를 실시간으로 보여주는 모니터.
 *
 * GET /monitor          → 전체 상태 한눈에
 * GET /monitor/memory   → JVM 힙/Non-Heap 상세
 * GET /monitor/threads  → Tomcat 스레드 풀 상태
 * GET /monitor/hikari   → HikariCP 커넥션 풀 상태
 * GET /monitor/buffers  → Direct Buffer 사용량
 * GET /monitor/netty    → Netty(Lettuce/Redis) 버퍼 풀 상태
 */
@RestController
@RequestMapping("/monitor")
@ConditionalOnBean(javax.sql.DataSource.class)
public class PoolMonitorController {

    private final DataSource dataSource;
    private final LettuceConnectionFactory redisConnectionFactory;

    public PoolMonitorController(DataSource dataSource,
                                  LettuceConnectionFactory redisConnectionFactory) {
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    // ========================================
    // 전체 상태
    // ========================================
    @GetMapping
    public Map<String, Object> all() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("memory", memory());
        result.put("threads", threads());
        result.put("hikariCP", hikari());
        result.put("directBuffers", buffers());
        result.put("nettyBufferPool", netty());
        return result;
    }

    // ========================================
    // 1. JVM 메모리 (힙 vs Non-Heap)
    // ========================================
    @GetMapping("/memory")
    public Map<String, Object> memory() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        Runtime rt = Runtime.getRuntime();

        Map<String, Object> heap = new LinkedHashMap<>();
        heap.put("used_MB", mem.getHeapMemoryUsage().getUsed() / 1024 / 1024);
        heap.put("committed_MB", mem.getHeapMemoryUsage().getCommitted() / 1024 / 1024);
        heap.put("max_MB", mem.getHeapMemoryUsage().getMax() / 1024 / 1024);
        heap.put("usage_percent", String.format("%.1f%%",
                (double) mem.getHeapMemoryUsage().getUsed() / mem.getHeapMemoryUsage().getMax() * 100));

        Map<String, Object> nonHeap = new LinkedHashMap<>();
        nonHeap.put("used_MB", mem.getNonHeapMemoryUsage().getUsed() / 1024 / 1024);
        nonHeap.put("committed_MB", mem.getNonHeapMemoryUsage().getCommitted() / 1024 / 1024);
        nonHeap.put("설명", "Metaspace + CodeCache + Compressed Class Space (스레드 스택, Direct Buffer 미포함)");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("heap", heap);
        result.put("nonHeap", nonHeap);
        result.put("totalJVM_MB", rt.totalMemory() / 1024 / 1024);
        result.put("freeInHeap_MB", rt.freeMemory() / 1024 / 1024);
        return result;
    }

    // ========================================
    // 2. 스레드 풀 상태
    // ========================================
    @GetMapping("/threads")
    public Map<String, Object> threads() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        // Tomcat 스레드 카운트
        long tomcatThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("http-nio") || t.getName().startsWith("exec-"))
                .count();

        long hikariThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().contains("HikariPool") || t.getName().contains("Hikari"))
                .count();

        long nettyThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().contains("lettuce") || t.getName().contains("nioEventLoop"))
                .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_live_threads", threadBean.getThreadCount());
        result.put("peak_threads", threadBean.getPeakThreadCount());
        result.put("daemon_threads", threadBean.getDaemonThreadCount());
        result.put("tomcat_worker_threads", tomcatThreads);
        result.put("hikari_threads", hikariThreads);
        result.put("netty_redis_threads", nettyThreads);
        result.put("estimated_stack_memory_MB", threadBean.getThreadCount() * 1); // 기본 1MB/스레드
        result.put("설명", "각 스레드는 기본 1MB 스택. 스레드 수 × 1MB = 스택 메모리 (Non-Heap, JVM 외부)");
        return result;
    }

    // ========================================
    // 3. HikariCP 커넥션 풀
    // ========================================
    @GetMapping("/hikari")
    public Map<String, Object> hikari() {
        Map<String, Object> result = new LinkedHashMap<>();

        if (dataSource instanceof HikariDataSource hikari) {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            if (pool != null) {
                result.put("total_connections", pool.getTotalConnections());
                result.put("active_connections", pool.getActiveConnections());
                result.put("idle_connections", pool.getIdleConnections());
                result.put("waiting_threads", pool.getThreadsAwaitingConnection());
                result.put("max_pool_size", hikari.getMaximumPoolSize());
                result.put("min_idle", hikari.getMinimumIdle());
                result.put("connection_timeout_ms", hikari.getConnectionTimeout());
                result.put("설명", Map.of(
                        "total", "현재 풀에 존재하는 전체 커넥션 수",
                        "active", "지금 쿼리 실행 중인 커넥션 (빌려간 것)",
                        "idle", "풀에서 대기 중인 커넥션 (반납된 것)",
                        "waiting", "커넥션을 기다리는 스레드 수 (이게 0 이상이면 풀 부족)"
                ));
            }
        }
        return result;
    }

    // ========================================
    // 4. Direct Buffer (JVM NIO)
    // ========================================
    @GetMapping("/buffers")
    public Map<String, Object> buffers() {
        List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);

        Map<String, Object> result = new LinkedHashMap<>();
        for (BufferPoolMXBean pool : pools) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("count", pool.getCount());
            info.put("used_MB", pool.getMemoryUsed() / 1024 / 1024);
            info.put("capacity_MB", pool.getTotalCapacity() / 1024 / 1024);
            info.put("used_bytes", pool.getMemoryUsed());
            result.put(pool.getName(), info);
        }
        result.put("설명", Map.of(
                "direct", "ByteBuffer.allocateDirect()로 할당된 버퍼. 힙 바깥, GC 대상 아님. Tomcat/JDBC/Netty가 사용",
                "mapped", "MappedByteBuffer (파일 메모리맵). 보통 0에 가까움"
        ));
        return result;
    }

    // ========================================
    // 5. Netty Buffer Pool (Redis Lettuce)
    // ========================================
    @GetMapping("/netty")
    public Map<String, Object> netty() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
            ByteBufAllocatorMetric metric = allocator.metric();

            result.put("used_heap_memory_bytes", metric.usedHeapMemory());
            result.put("used_direct_memory_bytes", metric.usedDirectMemory());
            result.put("used_direct_memory_MB", metric.usedDirectMemory() / 1024 / 1024);
            result.put("num_heap_arenas", allocator.metric().numHeapArenas());
            result.put("num_direct_arenas", allocator.metric().numDirectArenas());
            result.put("설명", Map.of(
                    "arena", "스레드 경합을 줄이기 위한 메모리 영역. 보통 CPU 코어 수 × 2",
                    "direct_memory", "Netty가 Redis 통신에 사용하는 Direct Buffer 총량"
            ));
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }

        return result;
    }
}
