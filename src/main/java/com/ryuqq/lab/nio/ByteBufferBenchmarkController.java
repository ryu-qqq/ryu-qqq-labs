package com.ryuqq.lab.nio;

import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/**
 * 힙 버퍼 vs 다이렉트 버퍼의 실제 성능 차이를 측정하는 벤치마크.
 *
 * 실험 포인트:
 *   1. 할당 속도: allocate() vs allocateDirect() → 100배 차이 체감
 *   2. I/O 속도: 파일 읽기/쓰기에서 복사 횟수 차이 → 대용량일수록 Direct 유리
 *   3. GC 영향: 힙 버퍼는 GC 대상, 다이렉트 버퍼는 GC 대상 아님
 */
@RestController
@RequestMapping("/nio/benchmark")
public class ByteBufferBenchmarkController {

    /**
     * 할당 속도 비교.
     * 힙 버퍼: Eden 영역에 포인터만 이동 (~10ns)
     * 다이렉트 버퍼: OS 시스템 콜 + 네이티브 메모리 할당 (~1000ns)
     */
    @GetMapping("/allocate")
    public Map<String, Object> benchmarkAllocate(
            @RequestParam(defaultValue = "8192") int size,
            @RequestParam(defaultValue = "10000") int iterations) {

        // 힙 버퍼 할당 측정
        long heapStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            ByteBuffer.allocate(size);
        }
        long heapNanos = System.nanoTime() - heapStart;

        // 다이렉트 버퍼 할당 측정
        long directStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            ByteBuffer.allocateDirect(size);
        }
        long directNanos = System.nanoTime() - directStart;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bufferSize", size + " bytes");
        result.put("iterations", iterations);

        Map<String, Object> heap = new LinkedHashMap<>();
        heap.put("totalMs", heapNanos / 1_000_000.0);
        heap.put("avgNanos", heapNanos / iterations);
        heap.put("설명", "Eden 영역에 byte[" + size + "] 할당. TLAB bump pointer로 초고속");

        Map<String, Object> direct = new LinkedHashMap<>();
        direct.put("totalMs", directNanos / 1_000_000.0);
        direct.put("avgNanos", directNanos / iterations);
        direct.put("설명", "OS에 malloc() 시스템 콜. 커널 모드 전환 비용 발생");

        result.put("heapBuffer", heap);
        result.put("directBuffer", direct);
        result.put("ratio", String.format("다이렉트가 %.1f배 느림", (double) directNanos / heapNanos));
        result.put("결론", "할당/해제가 빈번하면 힙 버퍼, I/O용으로 재사용하면 다이렉트 버퍼");

        return result;
    }

    /**
     * 파일 I/O 성능 비교.
     * 힙 버퍼: 커널 → 임시버퍼 → 힙 byte[] (복사 2번)
     * 다이렉트 버퍼: 커널 → 다이렉트 버퍼 (복사 1번)
     */
    @GetMapping("/io")
    public Map<String, Object> benchmarkIO(
            @RequestParam(defaultValue = "10") int fileSizeMB,
            @RequestParam(defaultValue = "8192") int bufferSize) throws IOException {

        // 테스트용 임시 파일 생성
        Path tempFile = Files.createTempFile("nio-benchmark-", ".dat");
        byte[] chunk = new byte[bufferSize];
        Arrays.fill(chunk, (byte) 'A');

        int totalChunks = (fileSizeMB * 1024 * 1024) / bufferSize;

        try {
            // 파일 쓰기 (테스트 데이터 준비)
            try (FileChannel writeChannel = FileChannel.open(tempFile,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer writeBuffer = ByteBuffer.allocateDirect(bufferSize);
                for (int i = 0; i < totalChunks; i++) {
                    writeBuffer.clear();
                    writeBuffer.put(chunk);
                    writeBuffer.flip();
                    writeChannel.write(writeBuffer);
                }
            }

            // 힙 버퍼로 읽기
            long heapReadNanos;
            try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.READ)) {
                ByteBuffer heapBuf = ByteBuffer.allocate(bufferSize);
                long start = System.nanoTime();
                while (channel.read(heapBuf) != -1) {
                    heapBuf.clear();
                }
                heapReadNanos = System.nanoTime() - start;
            }

            // 다이렉트 버퍼로 읽기
            long directReadNanos;
            try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.READ)) {
                ByteBuffer directBuf = ByteBuffer.allocateDirect(bufferSize);
                long start = System.nanoTime();
                while (channel.read(directBuf) != -1) {
                    directBuf.clear();
                }
                directReadNanos = System.nanoTime() - start;
            }

            // 힙 버퍼로 쓰기
            Path heapOut = Files.createTempFile("nio-heap-write-", ".dat");
            long heapWriteNanos;
            try (FileChannel channel = FileChannel.open(heapOut,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer heapBuf = ByteBuffer.allocate(bufferSize);
                long start = System.nanoTime();
                for (int i = 0; i < totalChunks; i++) {
                    heapBuf.clear();
                    heapBuf.put(chunk);
                    heapBuf.flip();
                    channel.write(heapBuf);
                }
                heapWriteNanos = System.nanoTime() - start;
            } finally {
                Files.deleteIfExists(heapOut);
            }

            // 다이렉트 버퍼로 쓰기
            Path directOut = Files.createTempFile("nio-direct-write-", ".dat");
            long directWriteNanos;
            try (FileChannel channel = FileChannel.open(directOut,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer directBuf = ByteBuffer.allocateDirect(bufferSize);
                long start = System.nanoTime();
                for (int i = 0; i < totalChunks; i++) {
                    directBuf.clear();
                    directBuf.put(chunk);
                    directBuf.flip();
                    channel.write(directBuf);
                }
                directWriteNanos = System.nanoTime() - start;
            } finally {
                Files.deleteIfExists(directOut);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("fileSize", fileSizeMB + "MB");
            result.put("bufferSize", bufferSize + " bytes");

            Map<String, Object> read = new LinkedHashMap<>();
            read.put("heapMs", heapReadNanos / 1_000_000.0);
            read.put("directMs", directReadNanos / 1_000_000.0);
            read.put("winner", heapReadNanos < directReadNanos ? "heap" : "direct");
            read.put("설명", "읽기: 커널→버퍼 복사. 힙은 중간 임시버퍼 경유(2번), 다이렉트는 직접(1번)");

            Map<String, Object> write = new LinkedHashMap<>();
            write.put("heapMs", heapWriteNanos / 1_000_000.0);
            write.put("directMs", directWriteNanos / 1_000_000.0);
            write.put("winner", heapWriteNanos < directWriteNanos ? "heap" : "direct");
            write.put("설명", "쓰기: 버퍼→커널 복사. 동일한 복사 횟수 차이");

            result.put("read", read);
            result.put("write", write);
            result.put("결론", "I/O 바운드 작업에서는 다이렉트 버퍼가 유리. 데이터가 클수록 차이 벌어짐");

            return result;

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * 버퍼 재사용 vs 매번 새로 할당 비교.
     * 풀링의 핵심: 비싼 할당을 한 번만 하고 clear()로 재사용.
     */
    @GetMapping("/reuse")
    public Map<String, Object> benchmarkReuse(
            @RequestParam(defaultValue = "8192") int size,
            @RequestParam(defaultValue = "100000") int iterations) {

        // 매번 새로 할당
        long newAllocStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            ByteBuffer buf = ByteBuffer.allocateDirect(size);
            buf.put((byte) 1);
            // buf는 버려짐 → GC Cleaner가 나중에 해제
        }
        long newAllocNanos = System.nanoTime() - newAllocStart;

        // 하나를 재사용 (풀링 시뮬레이션)
        ByteBuffer reusable = ByteBuffer.allocateDirect(size);
        long reuseStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            reusable.clear();
            reusable.put((byte) 1);
        }
        long reuseNanos = System.nanoTime() - reuseStart;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bufferSize", size + " bytes");
        result.put("iterations", iterations);

        Map<String, Object> newAlloc = new LinkedHashMap<>();
        newAlloc.put("totalMs", newAllocNanos / 1_000_000.0);
        newAlloc.put("avgNanos", newAllocNanos / iterations);
        newAlloc.put("설명", "매번 allocateDirect() + GC Cleaner 해제 대기");

        Map<String, Object> reuse = new LinkedHashMap<>();
        reuse.put("totalMs", reuseNanos / 1_000_000.0);
        reuse.put("avgNanos", reuseNanos / iterations);
        reuse.put("설명", "한 번 할당 후 clear()로 재사용. Tomcat/Netty가 이렇게 함");

        result.put("newEachTime", newAlloc);
        result.put("reuseWithClear", reuse);
        result.put("ratio", String.format("재사용이 %.1f배 빠름", (double) newAllocNanos / reuseNanos));
        result.put("결론", "이것이 버퍼 풀링의 존재 이유. HikariCP가 커넥션을 풀링하는 것과 동일한 원리");

        return result;
    }
}
