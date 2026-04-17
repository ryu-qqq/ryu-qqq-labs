package com.ryuqq.lab.nio;

import org.springframework.web.bind.annotation.*;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ByteBuffer의 내부 상태(position, limit, capacity)가
 * 각 연산(put, flip, get, clear, compact)마다 어떻게 변하는지 시각화하는 실험용 컨트롤러.
 *
 * 사용법:
 *   1. POST /nio/buffer/create?size=16          → 버퍼 생성
 *   2. POST /nio/buffer/{id}/put?data=Hello     → 데이터 쓰기
 *   3. POST /nio/buffer/{id}/flip               → 읽기 모드 전환
 *   4. POST /nio/buffer/{id}/get?count=3        → 데이터 읽기
 *   5. POST /nio/buffer/{id}/clear              → 초기화
 *   6. POST /nio/buffer/{id}/compact            → 안 읽은 데이터 앞으로 밀기
 *   7. GET  /nio/buffer/{id}                    → 현재 상태 조회
 *   8. GET  /nio/buffer/{id}/history            → 연산 히스토리 전체 조회
 */
@RestController
@RequestMapping("/nio/buffer")
public class ByteBufferLabController {

    private final Map<Integer, BufferSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger idGenerator = new AtomicInteger(0);

    // ── 버퍼 생성 ──

    @PostMapping("/create")
    public Map<String, Object> createHeapBuffer(@RequestParam(defaultValue = "16") int size) {
        int id = idGenerator.incrementAndGet();
        ByteBuffer buffer = ByteBuffer.allocate(size);
        BufferSession session = new BufferSession(id, buffer, false);
        session.recordHistory("allocate(" + size + ")", "힙 버퍼 생성. Eden 영역에 byte[] 할당 (~10ns)");
        sessions.put(id, session);
        return session.snapshot();
    }

    @PostMapping("/create-direct")
    public Map<String, Object> createDirectBuffer(@RequestParam(defaultValue = "16") int size) {
        int id = idGenerator.incrementAndGet();
        ByteBuffer buffer = ByteBuffer.allocateDirect(size);
        BufferSession session = new BufferSession(id, buffer, true);
        session.recordHistory("allocateDirect(" + size + ")", "다이렉트 버퍼 생성. OS에 시스템 콜 (~1000ns, 힙의 100배 느림). GC가 이동시키지 않음 → 커널이 직접 접근 가능");
        sessions.put(id, session);
        return session.snapshot();
    }

    // ── 쓰기 연산 ──

    @PostMapping("/{id}/put")
    public Map<String, Object> put(@PathVariable int id, @RequestParam String data) {
        BufferSession session = getSession(id);
        byte[] bytes = data.getBytes();

        if (session.buffer.remaining() < bytes.length) {
            throw new IllegalStateException(
                "공간 부족! remaining=" + session.buffer.remaining() + ", 필요=" + bytes.length +
                ". position이 limit에 도달함. clear() 또는 compact() 필요");
        }

        session.buffer.put(bytes);
        session.recordHistory(
            "put(\"" + data + "\")",
            "position이 " + (session.buffer.position() - bytes.length) + " → " + session.buffer.position() + "으로 이동. " +
            "데이터가 position 위치부터 순서대로 기록됨. remaining=" + session.buffer.remaining()
        );
        return session.snapshot();
    }

    @PostMapping("/{id}/put-byte")
    public Map<String, Object> putByte(@PathVariable int id, @RequestParam byte value) {
        BufferSession session = getSession(id);
        session.buffer.put(value);
        session.recordHistory(
            "put((byte)" + value + ")",
            "1바이트 기록. position " + (session.buffer.position() - 1) + " → " + session.buffer.position()
        );
        return session.snapshot();
    }

    // ── 모드 전환 ──

    @PostMapping("/{id}/flip")
    public Map<String, Object> flip(@PathVariable int id) {
        BufferSession session = getSession(id);
        int oldPosition = session.buffer.position();
        session.buffer.flip();
        session.recordHistory(
            "flip()",
            "쓰기→읽기 모드 전환. limit=" + oldPosition + "으로 설정(쓴 데이터 끝), position=0으로 리셋. " +
            "이제 0~" + (oldPosition - 1) + " 범위를 읽을 수 있음"
        );
        return session.snapshot();
    }

    @PostMapping("/{id}/rewind")
    public Map<String, Object> rewind(@PathVariable int id) {
        BufferSession session = getSession(id);
        session.buffer.rewind();
        session.recordHistory(
            "rewind()",
            "position=0으로 리셋. limit은 그대로. flip()과 다르게 limit을 건드리지 않음. 같은 데이터를 처음부터 다시 읽고 싶을 때 사용"
        );
        return session.snapshot();
    }

    // ── 읽기 연산 ──

    @PostMapping("/{id}/get")
    public Map<String, Object> get(@PathVariable int id, @RequestParam(defaultValue = "1") int count) {
        BufferSession session = getSession(id);

        if (session.buffer.remaining() < count) {
            throw new IllegalStateException(
                "읽을 데이터 부족! remaining=" + session.buffer.remaining() + ", 요청=" + count +
                ". flip() 했는지 확인. 안 했으면 position~limit 사이에 데이터가 없음");
        }

        byte[] result = new byte[count];
        int oldPos = session.buffer.position();
        session.buffer.get(result);
        String readData = new String(result);

        session.recordHistory(
            "get(" + count + ") → \"" + readData + "\"",
            "position이 " + oldPos + " → " + session.buffer.position() + "으로 이동. " +
            "읽은 바이트가 사라지는 게 아니라 position만 전진. remaining=" + session.buffer.remaining()
        );

        Map<String, Object> snapshot = session.snapshot();
        snapshot.put("readData", readData);
        snapshot.put("readBytes", bytesToIntArray(result));
        return snapshot;
    }

    // ── 초기화/정리 ──

    @PostMapping("/{id}/clear")
    public Map<String, Object> clear(@PathVariable int id) {
        BufferSession session = getSession(id);
        session.buffer.clear();
        session.recordHistory(
            "clear()",
            "position=0, limit=capacity로 리셋. 데이터를 실제로 지우지 않음! " +
            "다음 put()이 0번째부터 덮어쓸 뿐. Tomcat/HikariCP가 버퍼를 '반납'할 때 하는 게 이것"
        );
        return session.snapshot();
    }

    @PostMapping("/{id}/compact")
    public Map<String, Object> compact(@PathVariable int id) {
        BufferSession session = getSession(id);
        int remaining = session.buffer.remaining();
        session.buffer.compact();
        session.recordHistory(
            "compact()",
            "안 읽은 데이터(" + remaining + "바이트)를 버퍼 앞으로 복사. " +
            "position=" + session.buffer.position() + "(복사한 데이터 뒤), limit=capacity. " +
            "NIO에서 부분 읽기 후 이어서 쓸 때 사용. clear()와 달리 남은 데이터를 보존"
        );
        return session.snapshot();
    }

    // ── 상태 조회 ──

    @GetMapping("/{id}")
    public Map<String, Object> status(@PathVariable int id) {
        return getSession(id).snapshot();
    }

    @GetMapping("/{id}/history")
    public Map<String, Object> history(@PathVariable int id) {
        BufferSession session = getSession(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bufferId", id);
        result.put("isDirect", session.isDirect);
        result.put("history", session.history);
        return result;
    }

    @GetMapping("/sessions")
    public Map<String, Object> listSessions() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeSessions", sessions.size());
        List<Map<String, Object>> list = new ArrayList<>();
        sessions.forEach((sid, session) -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", sid);
            info.put("isDirect", session.isDirect);
            info.put("capacity", session.buffer.capacity());
            info.put("operationCount", session.history.size());
            list.add(info);
        });
        result.put("sessions", list);
        return result;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteSession(@PathVariable int id) {
        BufferSession session = sessions.remove(id);
        if (session == null) throw new IllegalArgumentException("세션 " + id + " 없음");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", id);
        result.put("message", session.isDirect
            ? "다이렉트 버퍼는 GC Cleaner가 나중에 해제. 즉시 반환되지 않음"
            : "힙 버퍼는 참조 끊기면 다음 Minor GC에서 회수");
        return result;
    }

    // ── 내부 ──

    private BufferSession getSession(int id) {
        BufferSession session = sessions.get(id);
        if (session == null) throw new IllegalArgumentException("세션 " + id + " 없음. POST /nio/buffer/create 먼저 호출");
        return session;
    }

    private int[] bytesToIntArray(byte[] bytes) {
        int[] result = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) result[i] = bytes[i] & 0xFF;
        return result;
    }

    // ── 세션 클래스 ──

    private static class BufferSession {
        final int id;
        final ByteBuffer buffer;
        final boolean isDirect;
        final List<Map<String, Object>> history = new ArrayList<>();

        BufferSession(int id, ByteBuffer buffer, boolean isDirect) {
            this.id = id;
            this.buffer = buffer;
            this.isDirect = isDirect;
        }

        void recordHistory(String operation, String explanation) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("step", history.size() + 1);
            entry.put("operation", operation);
            entry.put("explanation", explanation);
            entry.put("state", bufferState());
            entry.put("visualization", visualize());
            history.add(entry);
        }

        Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("bufferId", id);
            result.put("type", isDirect ? "DirectByteBuffer (네이티브 메모리)" : "HeapByteBuffer (JVM 힙)");
            result.put("state", bufferState());
            result.put("visualization", visualize());
            if (!history.isEmpty()) {
                result.put("lastOperation", history.get(history.size() - 1));
            }
            return result;
        }

        private Map<String, Object> bufferState() {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("position", buffer.position());
            state.put("limit", buffer.limit());
            state.put("capacity", buffer.capacity());
            state.put("remaining", buffer.remaining());
            state.put("hasRemaining", buffer.hasRemaining());

            // 버퍼 내용물 읽기 (position 변경 없이)
            byte[] contents = new byte[buffer.capacity()];
            ByteBuffer duplicate = buffer.duplicate();
            duplicate.clear();
            duplicate.get(contents);

            // 사람이 읽을 수 있는 형태로 변환
            StringBuilder ascii = new StringBuilder();
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < contents.length; i++) {
                if (i > 0) hex.append(" ");
                hex.append(String.format("%02X", contents[i]));
                ascii.append(contents[i] >= 32 && contents[i] < 127 ? (char) contents[i] : '·');
            }
            state.put("rawHex", hex.toString());
            state.put("rawAscii", ascii.toString());

            return state;
        }

        private String visualize() {
            int pos = buffer.position();
            int lim = buffer.limit();
            int cap = buffer.capacity();

            StringBuilder sb = new StringBuilder();

            // 인덱스 행
            sb.append("index:  ");
            for (int i = 0; i < cap; i++) sb.append(String.format("%-4d", i));
            sb.append("\n");

            // 데이터 행
            ByteBuffer dup = buffer.duplicate();
            dup.clear();
            sb.append("data:   ");
            for (int i = 0; i < cap; i++) {
                byte b = dup.get();
                if (b >= 32 && b < 127) {
                    sb.append(String.format("%-4c", (char) b));
                } else if (b == 0) {
                    sb.append("·   ");
                } else {
                    sb.append(String.format("%-4d", b & 0xFF));
                }
            }
            sb.append("\n");

            // 마커 행
            sb.append("marker: ");
            for (int i = 0; i < cap; i++) {
                List<String> markers = new ArrayList<>();
                if (i == pos) markers.add("P");
                if (i == lim) markers.add("L");
                if (i == cap - 1) markers.add("C=" + cap);

                if (!markers.isEmpty()) {
                    String label = "↑" + String.join(",", markers);
                    sb.append(label);
                    // 패딩
                    int pad = 4 - label.length();
                    if (pad > 0 && i < cap - 1) sb.append(" ".repeat(pad));
                } else {
                    sb.append("    ");
                }
            }

            return sb.toString();
        }
    }
}
