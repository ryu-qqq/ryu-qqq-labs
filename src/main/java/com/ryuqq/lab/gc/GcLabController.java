package com.ryuqq.lab.gc;

import org.springframework.web.bind.annotation.*;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * GC 동작을 관찰하기 위한 실험 컨트롤러.
 *
 * 실험 시나리오:
 *   1. 힙 채우기 → GC 발생 관찰 (Eden → Old Gen 이동, Minor/Major GC)
 *   2. 단명 객체 대량 생성 → Minor GC 빈도 관찰
 *   3. 장수 객체 누적 → Old Gen 증가 → Major GC / Full GC 관찰
 *   4. GC 종류별 비교 (G1GC, ZGC, Serial) → docker-compose JAVA_OPTS 변경
 *   5. 힙 크기별 비교 (-Xmx128m, 512m, 1g) → GC pause 차이
 */
@RestController
@RequestMapping("/gc")
public class GcLabController {

    // 장수 객체 보관소 (Old Gen으로 승격됨)
    private final List<byte[]> longLived = new CopyOnWriteArrayList<>();

    /**
     * 단명 객체 대량 생성.
     * Eden 영역을 빠르게 채워서 Minor GC를 유발.
     * 객체는 메서드 끝나면 바로 GC 대상 → Eden에서 바로 회수.
     */
    @PostMapping("/short-lived")
    public Map<String, Object> shortLived(
            @RequestParam(defaultValue = "100000") int count,
            @RequestParam(defaultValue = "1024") int sizeBytes) {

        long before = getEdenUsed();
        long gcCountBefore = getMinorGcCount();
        long start = System.nanoTime();

        for (int i = 0; i < count; i++) {
            byte[] temp = new byte[sizeBytes]; // 생성 즉시 버려짐 → Eden에서 회수
            temp[0] = 1; // 최적화 방지
        }

        long elapsed = System.nanoTime() - start;
        long gcCountAfter = getMinorGcCount();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", count + "개 x " + sizeBytes + "B = " + (count * (long) sizeBytes / 1024 / 1024) + "MB");
        result.put("elapsedMs", elapsed / 1_000_000);
        result.put("edenBefore", before / 1024 / 1024 + "MB");
        result.put("edenAfter", getEdenUsed() / 1024 / 1024 + "MB");
        result.put("minorGcTriggered", (gcCountAfter - gcCountBefore) + "회");
        result.put("설명", "단명 객체는 Eden에서 생성되고 Minor GC에서 즉시 회수. " +
                "count를 늘리면 Minor GC가 더 자주 발생. Grafana GC Pause Count 관찰.");
        return result;
    }

    /**
     * 장수 객체 누적.
     * 참조를 유지해서 GC가 회수 못 함 → Minor GC 때 Survivor를 거쳐 Old Gen으로 승격.
     * Old Gen이 차면 Major GC 발생.
     */
    @PostMapping("/long-lived")
    public Map<String, Object> longLived(
            @RequestParam(defaultValue = "10") int sizeMB,
            @RequestParam(defaultValue = "1") int count) {

        long oldGenBefore = getOldGenUsed();
        long majorGcBefore = getMajorGcCount();

        for (int i = 0; i < count; i++) {
            byte[] data = new byte[sizeMB * 1024 * 1024];
            data[0] = 1;
            longLived.add(data); // 참조 유지 → GC 회수 불가
        }

        long oldGenAfter = getOldGenUsed();
        long majorGcAfter = getMajorGcCount();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("added", sizeMB + "MB x " + count + "개 = " + (sizeMB * count) + "MB");
        result.put("totalHeld", longLived.size() + "개, " + longLived.stream().mapToLong(a -> a.length).sum() / 1024 / 1024 + "MB");
        result.put("oldGenBefore", oldGenBefore / 1024 / 1024 + "MB");
        result.put("oldGenAfter", oldGenAfter / 1024 / 1024 + "MB");
        result.put("majorGcTriggered", (majorGcAfter - majorGcBefore) + "회");
        result.put("설명", "참조를 유지하므로 GC가 회수 못 함. Minor GC를 거쳐 Old Gen으로 승격. " +
                "Old Gen이 차면 Major GC 발생 → pause가 길어짐. Grafana GC Pause Time 관찰.");
        result.put("해제", "POST /gc/release");
        return result;
    }

    /**
     * 장수 객체 해제.
     * 참조를 끊으면 다음 GC에서 회수 → Old Gen 감소.
     */
    @PostMapping("/release")
    public Map<String, Object> release() {
        int count = longLived.size();
        long totalMB = longLived.stream().mapToLong(a -> a.length).sum() / 1024 / 1024;
        long oldGenBefore = getOldGenUsed();

        longLived.clear();
        System.gc(); // 힌트

        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("released", count + "개, " + totalMB + "MB");
        result.put("oldGenBefore", oldGenBefore / 1024 / 1024 + "MB");
        result.put("oldGenAfter", getOldGenUsed() / 1024 / 1024 + "MB");
        result.put("설명", "참조 끊으면 Major GC에서 Old Gen 회수. Grafana Heap Memory에서 Old Gen 감소 관찰.");
        return result;
    }

    /**
     * 힙 압박 테스트.
     * 짧은 시간에 대량 할당/해제를 반복해서 GC를 극한으로 몰아붙임.
     * GC 종류별 pause 차이를 비교하기 좋음.
     */
    @PostMapping("/pressure")
    public Map<String, Object> pressure(
            @RequestParam(defaultValue = "10") int durationSeconds,
            @RequestParam(defaultValue = "1024") int objectSizeBytes) {

        long gcPauseBefore = getTotalGcPauseMs();
        long gcCountBefore = getTotalGcCount();
        long start = System.currentTimeMillis();
        long objectsCreated = 0;

        // 지정 시간 동안 계속 객체 생성 (단명)
        while (System.currentTimeMillis() - start < durationSeconds * 1000L) {
            for (int i = 0; i < 1000; i++) {
                byte[] temp = new byte[objectSizeBytes];
                temp[0] = 1;
                objectsCreated++;
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        long gcPauseAfter = getTotalGcPauseMs();
        long gcCountAfter = getTotalGcCount();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("duration", elapsed + "ms");
        result.put("objectsCreated", objectsCreated);
        result.put("objectSize", objectSizeBytes + "B");
        result.put("totalAllocated", (objectsCreated * objectSizeBytes / 1024 / 1024) + "MB");
        result.put("gcCount", (gcCountAfter - gcCountBefore) + "회");
        result.put("gcPauseTotal", (gcPauseAfter - gcPauseBefore) + "ms");
        result.put("설명", "GC 종류별 비교: 같은 압박에서 G1GC vs ZGC vs Serial의 pause 차이. " +
                "docker-compose의 JAVA_OPTS에서 GC를 바꾸고 재시작 후 비교.");
        return result;
    }

    /**
     * 현재 GC 및 힙 상태 조회.
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        Runtime rt = Runtime.getRuntime();

        Map<String, Object> heap = new LinkedHashMap<>();
        heap.put("usedMB", mem.getHeapMemoryUsage().getUsed() / 1024 / 1024);
        heap.put("maxMB", mem.getHeapMemoryUsage().getMax() / 1024 / 1024);
        heap.put("usagePercent", String.format("%.1f%%",
                (double) mem.getHeapMemoryUsage().getUsed() / mem.getHeapMemoryUsage().getMax() * 100));

        Map<String, Object> regions = new LinkedHashMap<>();
        regions.put("edenMB", getEdenUsed() / 1024 / 1024);
        regions.put("survivorMB", getSurvivorUsed() / 1024 / 1024);
        regions.put("oldGenMB", getOldGenUsed() / 1024 / 1024);

        Map<String, Object> gc = new LinkedHashMap<>();
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            Map<String, Object> gcInfo = new LinkedHashMap<>();
            gcInfo.put("count", gcBean.getCollectionCount());
            gcInfo.put("totalTimeMs", gcBean.getCollectionTime());
            gcInfo.put("avgPauseMs", gcBean.getCollectionCount() > 0
                    ? gcBean.getCollectionTime() / gcBean.getCollectionCount() : 0);
            gc.put(gcBean.getName(), gcInfo);
        }

        Map<String, Object> jvmFlags = new LinkedHashMap<>();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        List<String> relevantFlags = runtimeBean.getInputArguments().stream()
                .filter(f -> f.contains("Xm") || f.contains("GC") || f.contains("RAM")
                        || f.contains("MaxDirect") || f.contains("TieredCompilation")
                        || f.contains("PrintCompilation"))
                .toList();
        jvmFlags.put("flags", relevantFlags);

        Map<String, Object> held = new LinkedHashMap<>();
        held.put("longLivedCount", longLived.size());
        held.put("longLivedMB", longLived.stream().mapToLong(a -> a.length).sum() / 1024 / 1024);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("heap", heap);
        result.put("regions", regions);
        result.put("gcCollectors", gc);
        result.put("jvmFlags", jvmFlags);
        result.put("heldObjects", held);
        return result;
    }

    // ── 유틸 ──

    private long getEdenUsed() {
        return getMemoryPoolUsed("Eden");
    }

    private long getSurvivorUsed() {
        return getMemoryPoolUsed("Survivor");
    }

    private long getOldGenUsed() {
        return getMemoryPoolUsed("Old Gen", "Tenured");
    }

    private long getMemoryPoolUsed(String... nameContains) {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            for (String name : nameContains) {
                if (pool.getName().contains(name)) {
                    return pool.getUsage().getUsed();
                }
            }
        }
        return 0;
    }

    private long getMinorGcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .filter(gc -> gc.getName().contains("Young") || gc.getName().contains("Minor")
                        || gc.getName().contains("G1 Young") || gc.getName().contains("Copy")
                        || gc.getName().contains("ZGC") || gc.getName().contains("Scavenge"))
                .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .sum();
    }

    private long getMajorGcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .filter(gc -> gc.getName().contains("Old") || gc.getName().contains("Major")
                        || gc.getName().contains("MarkSweep") || gc.getName().contains("G1 Old")
                        || gc.getName().contains("G1 Mixed"))
                .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .sum();
    }

    private long getTotalGcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .sum();
    }

    private long getTotalGcPauseMs() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .sum();
    }
}
