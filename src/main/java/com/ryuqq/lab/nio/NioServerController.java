package com.ryuqq.lab.nio;

import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.*;

/**
 * NIO 에코 서버를 제어하고 관찰하는 컨트롤러.
 *
 * 사용법:
 *   1. POST /nio/server/start           → 에코 서버 시작 (포트 9090)
 *   2. POST /nio/server/connect         → 테스트 클라이언트 접속
 *   3. POST /nio/server/send?msg=Hello  → 메시지 보내고 에코 받기
 *   4. GET  /nio/server/status           → 서버 상태 (커넥션 수, 스레드 정보)
 *   5. GET  /nio/server/events           → 이벤트 로그 (accept, echo, disconnect)
 *   6. POST /nio/server/disconnect       → 클라이언트 연결 끊기
 *   7. POST /nio/server/stop             → 서버 종료
 */
@RestController
@RequestMapping("/nio/server")
public class NioServerController {

    private NioEchoServer server;
    private Thread serverThread;

    // 테스트 클라이언트들
    private final Map<Integer, SocketChannel> clients = new LinkedHashMap<>();
    private int clientIdSeq = 0;

    @PostMapping("/start")
    public Map<String, Object> start(@RequestParam(defaultValue = "9999") int port) {
        if (server != null && server.isRunning()) {
            throw new IllegalStateException("서버가 이미 실행 중. 포트: " + server.getPort());
        }

        server = new NioEchoServer(port);
        serverThread = new Thread(server, "nio-echo-server");
        serverThread.setDaemon(true);
        serverThread.start();

        // 서버가 뜰 때까지 잠깐 대기
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "서버 시작됨");
        result.put("port", port);
        result.put("serverThread", serverThread.getName());
        result.put("설명", "스레드 '" + serverThread.getName() + "' 1개가 Acceptor + Poller + Worker 역할을 전부 수행. " +
                   "이 스레드 하나로 클라이언트 여러 개를 동시에 처리함");
        result.put("다음", "POST /nio/server/connect 로 클라이언트 접속해보세요");
        return result;
    }

    @PostMapping("/connect")
    public Map<String, Object> connect() throws IOException {
        checkServerRunning();

        SocketChannel client = SocketChannel.open();
        client.configureBlocking(true);  // 클라이언트는 블로킹이어도 됨
        client.connect(new InetSocketAddress("localhost", server.getPort()));

        int clientId = ++clientIdSeq;
        clients.put(clientId, client);

        // 서버가 accept 이벤트 처리할 시간
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clientId", clientId);
        result.put("localAddress", client.getLocalAddress().toString());
        result.put("connectedTo", client.getRemoteAddress().toString());
        result.put("설명", "TCP 3-way handshake 완료. " +
                   "서버의 Acceptor가 이 커넥션을 accept()하고 Selector에 OP_READ로 등록했음. " +
                   "이제 이 채널에 데이터를 보내면 Selector가 감지함");
        result.put("현재 접속 수", clients.size());
        result.put("다음", "POST /nio/server/send?clientId=" + clientId + "&msg=Hello");
        return result;
    }

    @PostMapping("/send")
    public Map<String, Object> send(
            @RequestParam(defaultValue = "1") int clientId,
            @RequestParam String msg) throws IOException {
        checkServerRunning();

        SocketChannel client = clients.get(clientId);
        if (client == null || !client.isOpen()) {
            throw new IllegalArgumentException("클라이언트 " + clientId + " 없음. /connect 먼저");
        }

        // 메시지 전송
        ByteBuffer sendBuffer = ByteBuffer.allocate(256);
        sendBuffer.put(msg.getBytes());
        sendBuffer.flip();
        client.write(sendBuffer);

        // 에코 응답 수신
        ByteBuffer recvBuffer = ByteBuffer.allocate(256);
        // 서버가 응답할 시간
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        client.read(recvBuffer);
        recvBuffer.flip();
        byte[] responseBytes = new byte[recvBuffer.remaining()];
        recvBuffer.get(responseBytes);
        String response = new String(responseBytes).trim();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clientId", clientId);
        result.put("sent", msg);
        result.put("received", response);
        result.put("설명", "클라이언트가 '" + msg + "' 전송 → " +
                   "서버 Selector가 감지 → 같은 스레드('" + serverThread.getName() + "')가 " +
                   "read(buffer) → flip() → 파싱 → put(응답) → flip() → write() → clear() 수행. " +
                   "이 전체가 아까 실험한 ByteBuffer 사이클");
        return result;
    }

    @PostMapping("/disconnect")
    public Map<String, Object> disconnect(@RequestParam(defaultValue = "1") int clientId) throws IOException {
        SocketChannel client = clients.remove(clientId);
        if (client == null) {
            throw new IllegalArgumentException("클라이언트 " + clientId + " 없음");
        }
        client.close();

        // 서버가 disconnect 이벤트 처리할 시간
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clientId", clientId);
        result.put("status", "연결 끊김");
        result.put("남은 클라이언트", clients.size());
        result.put("설명", "TCP FIN 전송. 서버 Selector가 감지 → read()가 -1 리턴 → 채널 닫고 Selector에서 제거");
        return result;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();

        if (server == null || !server.isRunning()) {
            result.put("status", "서버 안 띄워짐");
            result.put("다음", "POST /nio/server/start");
            return result;
        }

        result.put("status", "실행 중");
        result.put("port", server.getPort());
        result.put("serverThread", serverThread.getName());
        result.put("serverThreadState", serverThread.getState().toString());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalAccepted", server.getTotalAccepted());
        stats.put("totalMessagesEchoed", server.getTotalMessagesEchoed());
        stats.put("registeredChannels", server.getRegisteredChannels());
        stats.put("connectedClients", server.getConnectedClients());
        result.put("stats", stats);

        result.put("핵심", "스레드 '" + serverThread.getName() + "' 1개가 " +
                   server.getRegisteredChannels() + "개 채널을 동시에 감시 중. " +
                   "이것이 NIO Selector의 힘 — 스레드 1개로 수천 개 커넥션 처리 가능");

        return result;
    }

    @GetMapping("/events")
    public Map<String, Object> events(@RequestParam(defaultValue = "20") int last) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (server == null) {
            result.put("events", List.of());
            return result;
        }

        List<Map<String, Object>> log = server.getEventLog();
        int from = Math.max(0, log.size() - last);
        result.put("totalEvents", log.size());
        result.put("showing", "최근 " + last + "개");
        result.put("events", log.subList(from, log.size()));
        return result;
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() throws IOException {
        if (server == null || !server.isRunning()) {
            throw new IllegalStateException("서버가 실행 중이 아님");
        }

        // 클라이언트 전부 닫기
        for (SocketChannel client : clients.values()) {
            if (client.isOpen()) client.close();
        }
        clients.clear();

        server.stop();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "서버 종료됨");
        result.put("totalAccepted", server.getTotalAccepted());
        result.put("totalMessagesEchoed", server.getTotalMessagesEchoed());
        return result;
    }

    private void checkServerRunning() {
        if (server == null || !server.isRunning()) {
            throw new IllegalStateException("서버가 안 띄워져 있음. POST /nio/server/start 먼저");
        }
    }
}
