package com.ryuqq.lab.nio;

import org.springframework.web.bind.annotation.*;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 톰캣 스레드 고갈 + 다이렉트 버퍼 누수를 시뮬레이션하는 컨트롤러.
 * Grafana에서 메트릭 변화를 관찰하기 위한 실험용.
 *
 * 실험 1: 톰캣 스레드 고갈
 *   - /nio/stress/thread-block?seconds=30 을 동시에 여러 번 호출
 *   - Grafana "Tomcat Threads" 패널에서 busy가 max에 찍히는 걸 확인
 *   - max에 도달하면 새 요청은 accept-count 큐에서 대기 → 큐도 차면 연결 거부
 *
 * 실험 2: 다이렉트 버퍼 누수
 *   - /nio/stress/buffer-leak?sizeMB=10&count=5 로 버퍼 할당하고 안 반납
 *   - Grafana "Direct Buffer Memory/Count" 패널에서 계속 올라가는 걸 확인
 *   - /nio/stress/buffer-release 로 해제 → GC Cleaner가 회수하는 과정 관찰
 */
@RestController
@RequestMapping("/nio/stress")
public class TomcatStressController {

    private final List<ByteBuffer> leakedBuffers = new CopyOnWriteArrayList<>();

    // ── 실험 1: 톰캣 스레드 고갈 ──

    @GetMapping("/thread-block")
    public Map<String, Object> threadBlock(@RequestParam(defaultValue = "10") int seconds) {
        String threadName = Thread.currentThread().getName();
        long start = System.currentTimeMillis();

        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("thread", threadName);
        result.put("blockedSeconds", seconds);
        result.put("actualMs", elapsed);
        result.put("설명", "Worker 스레드 '" + threadName + "'를 " + seconds + "초간 점유. " +
                   "이 동안 이 스레드는 다른 요청을 처리할 수 없음. " +
                   "max=20이면 이걸 20번 동시 호출하면 서버가 멈춤");
        return result;
    }

    @GetMapping("/thread-status")
    public Map<String, Object> threadStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentThread", Thread.currentThread().getName());

        // 톰캣 Worker 스레드 목록
        List<String> tomcatThreads = new ArrayList<>();
        List<String> busyThreads = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("http-nio-8080-exec")) {
                tomcatThreads.add(t.getName());
                if (t.getState() == Thread.State.TIMED_WAITING) {
                    // sleep 중 = thread-block에 의해 점유된 상태
                    busyThreads.add(t.getName() + " (" + t.getState() + ")");
                }
            }
        }

        result.put("totalWorkerThreads", tomcatThreads.size());
        result.put("blockedThreads", busyThreads.size());
        result.put("blockedList", busyThreads);
        return result;
    }

    // ── 실험 2: 다이렉트 버퍼 누수 ──

    @PostMapping("/buffer-leak")
    public Map<String, Object> bufferLeak(
            @RequestParam(defaultValue = "10") int sizeMB,
            @RequestParam(defaultValue = "1") int count) {

        long beforeMemory = getDirectBufferMemory();
        long beforeCount = getDirectBufferCount();

        for (int i = 0; i < count; i++) {
            ByteBuffer buf = ByteBuffer.allocateDirect(sizeMB * 1024 * 1024);
            // 실제로 쓰기를 해야 OS가 물리 메모리를 할당
            for (int j = 0; j < buf.capacity(); j += 4096) {
                buf.put(j, (byte) 1);
            }
            leakedBuffers.add(buf);  // 참조를 들고 있으니 GC가 회수 못 함 = 누수
        }

        long afterMemory = getDirectBufferMemory();
        long afterCount = getDirectBufferCount();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allocated", sizeMB + "MB x " + count + "개 = " + (sizeMB * count) + "MB");
        result.put("before", Map.of("memoryMB", beforeMemory / 1024 / 1024, "count", beforeCount));
        result.put("after", Map.of("memoryMB", afterMemory / 1024 / 1024, "count", afterCount));
        result.put("totalLeaked", leakedBuffers.size() + "개 버퍼 누수 중");
        result.put("설명", "allocateDirect()로 할당 후 참조를 유지해서 GC가 회수 못 함. " +
                   "Grafana Direct Buffer Memory/Count 패널에서 증가 확인. " +
                   "이게 실제 서비스에서 발생하면 OOM: Direct buffer memory로 서버 죽음");
        result.put("해제", "POST /nio/stress/buffer-release");
        return result;
    }

    @PostMapping("/buffer-release")
    public Map<String, Object> bufferRelease() {
        int count = leakedBuffers.size();
        long beforeMemory = getDirectBufferMemory();

        leakedBuffers.clear();
        System.gc();  // GC Cleaner에게 힌트

        // GC가 돌 시간
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        long afterMemory = getDirectBufferMemory();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("released", count + "개 버퍼 참조 해제");
        result.put("beforeMB", beforeMemory / 1024 / 1024);
        result.put("afterMB", afterMemory / 1024 / 1024);
        result.put("설명", "참조를 끊으면 GC Cleaner가 다이렉트 버퍼를 해제. " +
                   "즉시 줄지 않을 수 있음 → Grafana에서 서서히 감소하는 패턴 관찰");
        return result;
    }

    @GetMapping("/buffer-status")
    public Map<String, Object> bufferStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("leakedBufferCount", leakedBuffers.size());
        result.put("leakedTotalMB", leakedBuffers.stream()
                .mapToLong(ByteBuffer::capacity)
                .sum() / 1024 / 1024);
        result.put("directBufferMemoryMB", getDirectBufferMemory() / 1024 / 1024);
        result.put("directBufferCount", getDirectBufferCount());
        return result;
    }

    // ── 유틸 ──

    private long getDirectBufferMemory() {
        return ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(p -> "direct".equals(p.getName()))
                .findFirst()
                .map(BufferPoolMXBean::getMemoryUsed)
                .orElse(0L);
    }

    private long getDirectBufferCount() {
        return ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(p -> "direct".equals(p.getName()))
                .findFirst()
                .map(BufferPoolMXBean::getCount)
                .orElse(0L);
    }
}
