package com.ryuqq.lab.gc;

import org.springframework.web.bind.annotation.*;

import java.lang.management.*;
import java.util.*;

/**
 * JIT 컴파일러 동작을 관찰하기 위한 실험 컨트롤러.
 *
 * 실험 시나리오:
 *   1. 웜업 전/후 성능 차이 — 같은 연산을 반복하면 JIT가 최적화, 속도 향상
 *   2. 탈출 분석 — 객체가 메서드 밖으로 안 나가면 힙 대신 스택 할당 → GC 부담 0
 *   3. 인라이닝 — 작은 메서드가 호출부에 직접 삽입 → 호출 오버헤드 제거
 *   4. Code Cache 모니터링 — JIT 컴파일된 코드가 저장되는 곳, 용량 관찰
 *
 * JVM 시작 옵션으로 JIT 로그를 볼 수 있음:
 *   -XX:+PrintCompilation          → 컴파일 이벤트 로그
 *   -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining → 인라이닝 로그
 */
@RestController
@RequestMapping("/jit")
public class JitLabController {

    /**
     * JIT 웜업 효과 측정.
     * 같은 연산을 rounds번 반복하면서 각 라운드의 소요 시간을 측정.
     * 초반 라운드(인터프리터)는 느리고, 후반 라운드(JIT 컴파일 후)는 빨라짐.
     */
    @GetMapping("/warmup")
    public Map<String, Object> warmup(
            @RequestParam(defaultValue = "10") int rounds,
            @RequestParam(defaultValue = "1000000") int iterationsPerRound) {

        List<Map<String, Object>> roundResults = new ArrayList<>();

        for (int r = 0; r < rounds; r++) {
            long start = System.nanoTime();

            long sum = 0;
            for (int i = 0; i < iterationsPerRound; i++) {
                sum += compute(i);
            }

            long elapsed = System.nanoTime() - start;

            Map<String, Object> round = new LinkedHashMap<>();
            round.put("round", r + 1);
            round.put("elapsedUs", elapsed / 1000);
            round.put("result", sum); // 최적화 방지
            roundResults.add(round);
        }

        long firstRound = (long) roundResults.get(0).get("elapsedUs");
        long lastRound = (long) roundResults.get(roundResults.size() - 1).get("elapsedUs");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rounds", roundResults);
        result.put("speedup", String.format("%.1f배", (double) firstRound / lastRound));
        result.put("설명", "초반 라운드는 인터프리터로 실행 (느림). " +
                "JIT가 '이거 자주 쓰네?' 감지 → 네이티브 코드로 컴파일. " +
                "후반 라운드는 컴파일된 코드 실행 (빠름). 이것이 '웜업'.");
        return result;
    }

    /**
     * 탈출 분석 효과 측정.
     * 객체가 메서드 밖으로 나가지 않으면 JIT가 힙 대신 스택에 할당.
     * → 힙 할당 0 → GC 부담 0
     *
     * noEscape: 객체가 메서드 안에서만 사용 → 스택 할당 가능
     * escape: 객체가 리스트에 들어감 → 힙에 할당해야 함
     */
    @GetMapping("/escape-analysis")
    public Map<String, Object> escapeAnalysis(
            @RequestParam(defaultValue = "5000000") int iterations) {

        // 웜업 (JIT가 탈출 분석을 적용하도록)
        for (int i = 0; i < 100000; i++) {
            noEscapeWork(i);
            escapeWork(i);
        }

        // 탈출 안 하는 케이스
        long gcBefore1 = getMinorGcCount();
        long start1 = System.nanoTime();
        long sum1 = 0;
        for (int i = 0; i < iterations; i++) {
            sum1 += noEscapeWork(i);
        }
        long elapsed1 = System.nanoTime() - start1;
        long gcAfter1 = getMinorGcCount();

        // 탈출하는 케이스
        List<int[]> escaped = new ArrayList<>();
        long gcBefore2 = getMinorGcCount();
        long start2 = System.nanoTime();
        long sum2 = 0;
        for (int i = 0; i < iterations; i++) {
            int[] arr = escapeWork(i);
            if (i % 100 == 0) escaped.add(arr); // 일부만 유지
            sum2 += arr[0];
        }
        long elapsed2 = System.nanoTime() - start2;
        long gcAfter2 = getMinorGcCount();

        escaped.clear(); // 정리

        Map<String, Object> noEscape = new LinkedHashMap<>();
        noEscape.put("elapsedMs", elapsed1 / 1_000_000);
        noEscape.put("gcTriggered", (gcAfter1 - gcBefore1) + "회");
        noEscape.put("sum", sum1);

        Map<String, Object> escape = new LinkedHashMap<>();
        escape.put("elapsedMs", elapsed2 / 1_000_000);
        escape.put("gcTriggered", (gcAfter2 - gcBefore2) + "회");
        escape.put("sum", sum2);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("iterations", iterations);
        result.put("noEscape", noEscape);
        result.put("escape", escape);
        result.put("설명", "noEscape: 객체가 메서드 안에서만 사용 → JIT가 스택 할당 → GC 0회. " +
                "escape: 객체가 밖으로 나감 → 힙 할당 → GC 발생. " +
                "탈출 분석 덕분에 단명 객체를 힙에 안 만들어도 됨.");
        return result;
    }

    /**
     * 인라이닝 효과 측정.
     * 작은 메서드를 호출 vs 직접 계산 → JIT가 인라이닝하면 차이 없어짐.
     */
    @GetMapping("/inlining")
    public Map<String, Object> inlining(
            @RequestParam(defaultValue = "10000000") int iterations) {

        // 웜업
        for (int i = 0; i < 100000; i++) {
            addMethod(i, i + 1);
        }

        // 메서드 호출
        long start1 = System.nanoTime();
        long sum1 = 0;
        for (int i = 0; i < iterations; i++) {
            sum1 += addMethod(i, i + 1);
        }
        long elapsed1 = System.nanoTime() - start1;

        // 직접 계산
        long start2 = System.nanoTime();
        long sum2 = 0;
        for (int i = 0; i < iterations; i++) {
            sum2 += (long) i + (i + 1); // 인라인된 것과 동일한 연산
        }
        long elapsed2 = System.nanoTime() - start2;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("iterations", iterations);

        Map<String, Object> method = new LinkedHashMap<>();
        method.put("elapsedMs", elapsed1 / 1_000_000);
        method.put("sum", sum1);

        Map<String, Object> direct = new LinkedHashMap<>();
        direct.put("elapsedMs", elapsed2 / 1_000_000);
        direct.put("sum", sum2);

        result.put("methodCall", method);
        result.put("directCalc", direct);
        result.put("설명", "JIT 인라이닝 적용 후 메서드 호출과 직접 계산의 성능이 거의 동일해짐. " +
                "JIT가 addMethod()의 본문을 호출부에 직접 삽입하기 때문. " +
                "-XX:+PrintCompilation 옵션으로 인라이닝 로그 확인 가능.");
        return result;
    }

    /**
     * Code Cache 상태 조회.
     * JIT 컴파일된 네이티브 코드가 저장되는 곳.
     */
    @GetMapping("/code-cache")
    public Map<String, Object> codeCache() {
        Map<String, Object> result = new LinkedHashMap<>();

        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getName().contains("CodeHeap") || pool.getName().contains("Code Cache")) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("usedMB", pool.getUsage().getUsed() / 1024 / 1024);
                info.put("maxMB", pool.getUsage().getMax() / 1024 / 1024);
                info.put("usagePercent", pool.getUsage().getMax() > 0
                        ? String.format("%.1f%%", (double) pool.getUsage().getUsed() / pool.getUsage().getMax() * 100)
                        : "N/A");
                result.put(pool.getName(), info);
            }
        }

        CompilationMXBean comp = ManagementFactory.getCompilationMXBean();
        if (comp != null) {
            Map<String, Object> compInfo = new LinkedHashMap<>();
            compInfo.put("name", comp.getName());
            compInfo.put("totalCompilationTimeMs", comp.getTotalCompilationTime());
            result.put("compiler", compInfo);
        }

        result.put("설명", "CodeHeap 'profiled nmethods': C1 컴파일 코드 저장. " +
                "CodeHeap 'non-profiled nmethods': C2 컴파일 코드 저장. " +
                "다 차면 JIT가 컴파일을 멈춤 → 성능 저하. Grafana Code Cache 패널 관찰.");
        return result;
    }

    /**
     * JIT 컴파일러 + GC 종합 상태.
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uptime", runtime.getUptime() / 1000 + "초");

        // JVM 플래그
        List<String> flags = runtime.getInputArguments().stream()
                .filter(f -> f.contains("Xm") || f.contains("GC") || f.contains("Tiered")
                        || f.contains("Compilation") || f.contains("Inline")
                        || f.contains("EscapeAnalysis") || f.contains("MaxDirect")
                        || f.contains("RAM") || f.contains("CodeCache"))
                .toList();
        result.put("jvmFlags", flags);

        // GC 정보
        Map<String, Object> gc = new LinkedHashMap<>();
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            gc.put(gcBean.getName(), Map.of(
                    "count", gcBean.getCollectionCount(),
                    "totalMs", gcBean.getCollectionTime()
            ));
        }
        result.put("gc", gc);

        // 컴파일러 정보
        CompilationMXBean comp = ManagementFactory.getCompilationMXBean();
        if (comp != null) {
            result.put("jitCompiler", comp.getName());
            result.put("totalCompilationMs", comp.getTotalCompilationTime());
        }

        return result;
    }

    // ── 실험용 메서드들 ──

    private long compute(int input) {
        // 의도적으로 약간 복잡한 연산 (JIT 최적화 대상)
        long result = input;
        result = result * 31 + 17;
        result = result ^ (result >>> 16);
        result = result * 0x45d9f3b;
        return result;
    }

    // 탈출 안 함 → JIT가 스택 할당 가능
    private long noEscapeWork(int input) {
        int[] pair = new int[]{input, input * 2}; // 메서드 밖으로 안 나감
        return pair[0] + pair[1];
    }

    // 탈출함 → 힙 할당 필수
    private int[] escapeWork(int input) {
        return new int[]{input, input * 2}; // return으로 밖에 나감
    }

    private long addMethod(int a, int b) {
        return (long) a + b; // 아주 작은 메서드 → 인라이닝 대상
    }

    private long getMinorGcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .filter(gc -> gc.getName().contains("Young") || gc.getName().contains("Minor")
                        || gc.getName().contains("G1 Young") || gc.getName().contains("Copy")
                        || gc.getName().contains("Scavenge"))
                .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .sum();
    }
}
