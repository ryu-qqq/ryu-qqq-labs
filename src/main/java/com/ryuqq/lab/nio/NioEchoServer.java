package com.ryuqq.lab.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tomcat NIO 구조를 단순화한 미니 에코 서버.
 *
 * Tomcat의 3종 스레드를 1개 스레드로 압축해서 보여줌:
 *   - Acceptor 역할: accept()로 새 커넥션 받기
 *   - Poller 역할:   Selector로 데이터 온 채널 감시
 *   - Worker 역할:   ByteBuffer로 읽고 에코 응답
 *
 * 스레드 1개로 클라이언트 여러 개를 동시에 처리하는 게 핵심.
 * 이게 NIO의 존재 이유야.
 */
public class NioEchoServer implements Runnable {

    private final int port;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Map<String, Object>> eventLog = new CopyOnWriteArrayList<>();
    private final List<String> connectedClients = new CopyOnWriteArrayList<>();

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private int totalAccepted = 0;
    private int totalMessagesEchoed = 0;

    public NioEchoServer(int port) {
        this.port = port;
    }

    @Override
    public void run() {
        try {
            // ── 서버 소켓 열기 ──
            selector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(port));
            serverChannel.configureBlocking(false);  // 논블로킹 모드!

            // Selector에 "새 커넥션 올 때 알려줘" 등록
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            running.set(true);
            log("SERVER_START", "포트 " + port + "에서 대기 시작. 스레드: " + Thread.currentThread().getName(),
                "ServerSocketChannel을 Selector에 OP_ACCEPT로 등록. 이제 Selector가 새 커넥션을 감시함");

            // ── 메인 이벤트 루프 (Tomcat의 Poller에 해당) ──
            while (running.get()) {
                // 이벤트가 올 때까지 최대 1초 대기
                int readyCount = selector.select(1000);

                if (readyCount == 0) continue;  // 아무 이벤트 없으면 다시 대기

                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();

                    if (!key.isValid()) continue;

                    if (key.isAcceptable()) {
                        // ── Acceptor 역할: 새 커넥션 수락 ──
                        handleAccept(key);
                    } else if (key.isReadable()) {
                        // ── Worker 역할: 데이터 읽고 에코 ──
                        handleRead(key);
                    }
                }
            }
        } catch (IOException e) {
            log("ERROR", e.getMessage(), "서버 에러 발생");
        } finally {
            cleanup();
        }
    }

    /**
     * Acceptor 역할.
     * 커널의 TCP backlog에서 완성된 커넥션을 꺼내서 Selector에 등록.
     */
    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();  // TCP handshake 완료된 커넥션 꺼냄

        if (client == null) return;

        client.configureBlocking(false);  // 논블로킹!

        // 이 클라이언트 채널을 Selector에 "데이터 오면 알려줘"로 등록
        // ByteBuffer를 attachment로 붙여줌 → 이 채널 전용 버퍼
        ByteBuffer buffer = ByteBuffer.allocateDirect(256);
        client.register(selector, SelectionKey.OP_READ, buffer);

        totalAccepted++;
        String clientAddr = client.getRemoteAddress().toString();
        connectedClients.add(clientAddr);

        log("ACCEPT", "새 커넥션: " + clientAddr + " (현재 " + totalAccepted + "번째)",
            "TCP 3-way handshake 완료된 커넥션을 accept(). " +
            "SocketChannel을 Selector에 OP_READ로 등록 + 전용 ByteBuffer(256B) 할당. " +
            "이 시점부터 Selector가 이 채널의 데이터 도착을 감시");
    }

    /**
     * Worker 역할.
     * SocketChannel에서 ByteBuffer로 읽고, 에코 응답.
     * put → flip → write → clear 사이클이 여기서 일어남.
     */
    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        ByteBuffer buffer = (ByteBuffer) key.attachment();  // accept 때 붙여둔 버퍼

        // ── socketChannel.read(buffer): 커널 소켓 버퍼 → ByteBuffer 복사 ──
        int bytesRead = client.read(buffer);  // 내부적으로 buffer.put() 에 해당

        if (bytesRead == -1) {
            // 클라이언트가 연결 끊음
            String clientAddr = client.getRemoteAddress().toString();
            connectedClients.remove(clientAddr);
            client.close();
            key.cancel();
            log("DISCONNECT", "연결 종료: " + clientAddr,
                "read()가 -1 리턴 = 클라이언트가 TCP FIN 보냄. 채널 닫고 Selector에서 제거");
            return;
        }

        if (bytesRead > 0) {
            // ── flip(): 쓰기→읽기 모드 전환 ──
            buffer.flip();

            // 읽은 데이터 확인
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            String message = new String(data).trim();

            // ── 에코 응답: ByteBuffer에 쓰고 채널로 전송 ──
            buffer.clear();
            String echo = "[에코] " + message + "\n";
            buffer.put(echo.getBytes());
            buffer.flip();  // 다시 읽기 모드로 (write가 버퍼에서 "읽어서" 커널로 보내니까)

            // ── socketChannel.write(buffer): ByteBuffer → 커널 소켓 버퍼 복사 ──
            client.write(buffer);

            // ── clear(): 버퍼 재사용 ──
            buffer.clear();

            totalMessagesEchoed++;

            log("ECHO", "'" + message + "' → " + client.getRemoteAddress(),
                "read(" + bytesRead + "B) → flip() → 메시지 파싱 → " +
                "응답 put() → flip() → write() → clear(). " +
                "이 전체 사이클이 아까 실험한 ByteBuffer 사이클과 동일");
        }
    }

    public void stop() {
        running.set(false);
        if (selector != null) selector.wakeup();  // select() 대기를 깨움
        log("SERVER_STOP", "서버 종료 요청", "running=false 설정 후 selector.wakeup()으로 이벤트 루프 탈출");
    }

    private void cleanup() {
        try {
            if (serverChannel != null) serverChannel.close();
            if (selector != null) selector.close();
        } catch (IOException ignored) {}
        running.set(false);
        log("SERVER_CLOSED", "서버 완전 종료", "ServerSocketChannel, Selector 닫음");
    }

    private void log(String event, String message, String explanation) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", System.currentTimeMillis());
        entry.put("thread", Thread.currentThread().getName());
        entry.put("event", event);
        entry.put("message", message);
        entry.put("explanation", explanation);
        eventLog.add(entry);
        // 최근 100개만 유지
        while (eventLog.size() > 100) eventLog.remove(0);
    }

    // ── 상태 조회용 ──

    public boolean isRunning() { return running.get(); }
    public int getPort() { return port; }
    public int getTotalAccepted() { return totalAccepted; }
    public int getTotalMessagesEchoed() { return totalMessagesEchoed; }
    public List<String> getConnectedClients() { return new ArrayList<>(connectedClients); }
    public List<Map<String, Object>> getEventLog() { return new ArrayList<>(eventLog); }
    public int getRegisteredChannels() {
        if (selector == null || !selector.isOpen()) return 0;
        return selector.keys().size() - 1;  // ServerSocketChannel 제외
    }
}
