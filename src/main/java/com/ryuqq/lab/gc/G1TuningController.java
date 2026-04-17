package com.ryuqq.lab.gc;

import org.springframework.web.bind.annotation.*;

import java.lang.management.*;
import java.util.*;

/**
 * G1GC 튜닝 파라미터와 컨테이너 환경 JVM 설정을 실험하는 컨트롤러.
 *
 * 실험 5: G1GC 튜닝
 *   - MaxGCPauseMillis: 목표 pause 시간 (기본 200ms)
 *     → 10ms로 낮추면 GC가 더 자주, 더 작게 수집
 *     → 500ms로 높이면 GC가 덜 자주, 한번에 많이 수집
 *   - G1HeapRegionSize: Region 크기 (기본 자동, 1~32MB)
 *
 * 실험 8: 컨테이너 환경 JVM 설정
 *   - UseContainerSupport: 컨테이너 메모리 한도 인식 (기본 true)
 *   - MaxRAMPercentage vs Xmx 차이
 *
 * docker-compose JAVA_OPTS 예시:
 *   -XX:MaxGCPauseMillis=10   → pause 10ms 목표
 *   -XX:MaxGCPauseMillis=500  → pause 500ms 허용
 *   -XX:G1HeapRegionSize=4m   → Region 4MB 고정
 */
@RestController
@RequestMapping("/gc/tuning")
public class G1TuningController {

    /**
     * 현재 JVM 설정 전체 조회.
     * GC 종류, 힙 크기, 튜닝 파라미터, 컨테이너 설정 등.
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();

        Map<String, Object> result = new LinkedHashMap<>();

        // JVM 플래그 전체
        result.put("allJvmFlags", runtime.getInputArguments());

        // 힙 설정
        Map<String, Object> heap = new LinkedHashMap<>();
        heap.put("maxMB", memory.getHeapMemoryUsage().getMax() / 1024 / 1024);
        heap.put("committedMB", memory.getHeapMemoryUsage().getCommitted() / 1024 / 1024);
        heap.put("usedMB", memory.getHeapMemoryUsage().getUsed() / 1024 / 1024);
        result.put("heap", heap);

        // GC 종류
        List<String> gcNames = new ArrayList<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcNames.add(gc.getName());
        }
        result.put("gcCollectors", gcNames);

        // G1GC Region 정보 (메모리풀에서 추출)
        Map<String, Object> regions = new LinkedHashMap<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getName().contains("G1")) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("usedMB", pool.getUsage().getUsed() / 1024 / 1024);
                info.put("maxMB", pool.getUsage().getMax() > 0
                        ? pool.getUsage().getMax() / 1024 / 1024 : "unlimited");
                info.put("committedMB", pool.getUsage().getCommitted() / 1024 / 1024);
                regions.put(pool.getName(), info);
            }
        }
        if (!regions.isEmpty()) {
            result.put("g1Regions", regions);
        }

        // 컨테이너 환경 정보
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        container.put("totalMemoryMB", Runtime.getRuntime().totalMemory() / 1024 / 1024);
        container.put("maxMemoryMB", Runtime.getRuntime().maxMemory() / 1024 / 1024);
        container.put("설명", "컨테이너에서 UseContainerSupport=true(기본값)이면 " +
                "availableProcessors와 maxMemory가 컨테이너 한도를 반영. " +
                "false이면 호스트 전체 리소스가 보임 → 과다 할당 위험");
        result.put("container", container);

        return result;
    }

    /**
     * MaxGCPauseMillis 효과 측정.
     * 같은 부하에서 pause 목표가 다르면 GC 행동이 어떻게 달라지는지.
     *
     * 사용법:
     *   1. -XX:MaxGCPauseMillis=10 으로 설정 → 이 API 호출 → 결과 기록
     *   2. -XX:MaxGCPauseMillis=200 으로 변경 → 재시작 → 같은 API 호출 → 비교
     */
    @PostMapping("/pause-target-test")
    public Map<String, Object> pauseTargetTest(
            @RequestParam(defaultValue = "10") int durationSeconds,
            @RequestParam(defaultValue = "2048") int objectSizeBytes,
            @RequestParam(defaultValue = "10") int longLivedMB) {

        // 장수 객체로 Old Gen에 압박
        List<byte[]> held = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            byte[] data = new byte[longLivedMB * 1024 * 1024];
            data[0] = 1;
            held.add(data);
        }

        // GC 통계 수집
        long gcCountBefore = getTotalGcCount();
        long gcTimeBefore = getTotalGcTime();
        long start = System.currentTimeMillis();
        long objectsCreated = 0;
        long maxPause = 0;

        // pause 측정을 위한 세밀한 루프
        while (System.currentTimeMillis() - start < durationSeconds * 1000L) {
            long loopStart = System.nanoTime();

            for (int i = 0; i < 500; i++) {
                byte[] temp = new byte[objectSizeBytes];
                temp[0] = 1;
                objectsCreated++;
            }

            long loopElapsed = (System.nanoTime() - loopStart) / 1_000_000;
            if (loopElapsed > maxPause) {
                maxPause = loopElapsed;
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        long gcCountAfter = getTotalGcCount();
        long gcTimeAfter = getTotalGcTime();

        // 장수 객체 해제
        held.clear();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("duration", elapsed + "ms");
        result.put("objectsCreated", objectsCreated);
        result.put("objectSize", objectSizeBytes + "B");
        result.put("longLivedPressure", (longLivedMB * 5) + "MB in Old Gen");
        result.put("gcCount", (gcCountAfter - gcCountBefore));
        result.put("gcTotalPauseMs", (gcTimeAfter - gcTimeBefore));
        result.put("gcAvgPauseMs", (gcCountAfter - gcCountBefore) > 0
                ? (gcTimeAfter - gcTimeBefore) / (gcCountAfter - gcCountBefore) : 0);
        result.put("observedMaxPauseMs", maxPause);

        // 현재 JVM 플래그에서 MaxGCPauseMillis 찾기
        String pauseTarget = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(f -> f.contains("MaxGCPauseMillis"))
                .findFirst()
                .orElse("기본값 (200ms)");
        result.put("currentPauseTarget", pauseTarget);

        result.put("설명", "같은 부하에서 MaxGCPauseMillis를 바꾸면: " +
                "낮으면 → GC 자주, pause 짧음, throughput 감소. " +
                "높으면 → GC 덜 자주, pause 길어질 수 있음, throughput 증가. " +
                "docker-compose에서 값 바꾸고 재시작 후 비교.");
        return result;
    }

    /**
     * 힙 크기별 GC 영향 비교.
     * 현재 힙 크기에서 동일한 부하를 걸어서 GC 행동 관찰.
     *
     * 사용법:
     *   1. -Xmx128m 으로 설정 → 이 API 호출 → 결과 기록
     *   2. -Xmx512m 으로 변경 → 재시작 → 같은 API 호출 → 비교
     *   3. -Xmx1g 으로 변경 → 재시작 → 같은 API 호출 → 비교
     */
    @PostMapping("/heap-size-test")
    public Map<String, Object> heapSizeTest(
            @RequestParam(defaultValue = "10") int durationSeconds) {

        long gcCountBefore = getTotalGcCount();
        long gcTimeBefore = getTotalGcTime();
        long start = System.currentTimeMillis();
        long objectsCreated = 0;

        while (System.currentTimeMillis() - start < durationSeconds * 1000L) {
            // 다양한 크기의 객체를 만들어서 실제 워크로드 시뮬레이션
            for (int i = 0; i < 100; i++) {
                byte[] small = new byte[256];    // API 요청 파싱
                byte[] medium = new byte[4096];  // JSON 응답 생성
                byte[] large = new byte[65536];  // DB 결과셋
                small[0] = 1;
                medium[0] = 1;
                large[0] = 1;
                objectsCreated += 3;
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        long gcCountAfter = getTotalGcCount();
        long gcTimeAfter = getTotalGcTime();

        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("heapMax", mem.getHeapMemoryUsage().getMax() / 1024 / 1024 + "MB");
        result.put("duration", elapsed + "ms");
        result.put("objectsCreated", objectsCreated);
        result.put("gcCount", (gcCountAfter - gcCountBefore));
        result.put("gcTotalPauseMs", (gcTimeAfter - gcTimeBefore));
        result.put("gcAvgPauseMs", (gcCountAfter - gcCountBefore) > 0
                ? (gcTimeAfter - gcTimeBefore) / (gcCountAfter - gcCountBefore) : 0);
        result.put("설명", "힙이 작으면 GC 자주 + pause 짧음. 힙이 크면 GC 덜 자주 + pause 길어질 수 있음. " +
                "-Xmx128m / 512m / 1g 로 바꿔가며 비교.");
        return result;
    }

    /**
     * 컨테이너 메모리 인식 확인.
     * UseContainerSupport가 켜져있으면 컨테이너 한도를 인식.
     */
    @GetMapping("/container-info")
    public Map<String, Object> containerInfo() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("availableProcessors", os.getAvailableProcessors());
        result.put("arch", os.getArch());
        result.put("osName", os.getName());
        result.put("jvmMaxHeapMB", Runtime.getRuntime().maxMemory() / 1024 / 1024);

        // MaxRAMPercentage vs Xmx 확인
        boolean hasXmx = runtime.getInputArguments().stream()
                .anyMatch(f -> f.startsWith("-Xmx"));
        boolean hasRAMPercentage = runtime.getInputArguments().stream()
                .anyMatch(f -> f.contains("MaxRAMPercentage"));

        result.put("heapSetting", hasXmx ? "Xmx (고정값)" : hasRAMPercentage ? "MaxRAMPercentage (비율)" : "기본값");

        result.put("설명", Map.of(
                "Xmx", "-Xmx512m → 힙 512MB 고정. 컨테이너 크기 바꿔도 힙 안 변함",
                "MaxRAMPercentage", "-XX:MaxRAMPercentage=50 → 컨테이너 메모리의 50%를 힙으로. " +
                        "컨테이너 2GB → 힙 1GB, 컨테이너 4GB → 힙 2GB. 유연함",
                "UseContainerSupport", "기본 true. false면 호스트 전체 메모리를 보고 힙을 잡아서 " +
                        "컨테이너 한도 초과 → OOM Kill 위험"
        ));

        return result;
    }

    // ── 유틸 ──

    private long getTotalGcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .sum();
    }

    private long getTotalGcTime() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .sum();
    }
}
