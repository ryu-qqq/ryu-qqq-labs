# 02. Direct Buffer 심화

## 일반 I/O vs NIO 비교

### 일반 I/O (Heap Buffer) - 복사 2번

```
커널 소켓 버퍼: [GET /api/v1/products/group HTTP/1.1\r\nHost: ...]
       ↓ (1) 커널이 임시 버퍼에 복사
  커널 임시 버퍼: [GET /api/v1/products/group HTTP/1.1\r\nHost: ...]
       ↓ (2) 커널이 JVM 힙 byte[]에 복사
  JVM 힙 byte[]: [GET /api/v1/products/group HTTP/1.1\r\nHost: ...]
```

왜 2번인가?
- 커널 공간과 유저 공간(JVM)은 메모리가 분리되어 있음
- JVM 힙은 GC가 객체를 이동시킬 수 있음 (compaction)
- 커널이 데이터를 쓰는 중에 GC가 byte[]를 옮겨버리면 메모리 corruption
- 그래서 커널은 "절대 안 움직이는" 임시 버퍼에 먼저 복사 → 힙으로 옮김

### NIO (Direct Buffer) - 복사 1번

```
커널 소켓 버퍼: [GET /api/v1/products/group HTTP/1.1\r\nHost: ...]
       ↓ (1) 커널이 Direct Buffer에 직접 복사
  Direct Buffer: [GET /api/v1/products/group HTTP/1.1\r\nHost: ...]
       ↓ 애플리케이션이 바로 여기서 읽음. 끝.
```

- Direct Buffer는 힙 바깥, 네이티브 메모리
- GC가 절대 건드리지 않으므로 주소가 안 변함
- 커널이 직접 읽고 쓸 수 있음 → 중간 임시 버퍼 불필요

### HTTP 요청 1건 전체 복사 횟수

```
                        일반 I/O (BIO)    NIO (Direct Buffer)
HTTP 요청 수신              2번               1번
DB 쿼리 전송                2번               1번
DB 결과 수신                2번               1번
HTTP 응답 전송              2번               1번
합계                        8번               4번
```

## 왜 처음부터 Direct Buffer만 안 쓰는가

할당/해제 속도 차이:

```
힙 버퍼 (new byte[8192]):
  할당: ~10 나노초 (Eden 영역에 포인터만 옮기면 끝)
  해제: GC가 알아서 (빠름)

Direct Buffer (ByteBuffer.allocateDirect(8192)):
  할당: ~1,000 나노초 (OS에 시스템 콜, 100배 느림)
  해제: GC Cleaner라는 특수 메커니즘으로 지연 해제
```

용도가 나뉜다:
- 비즈니스 로직: 객체를 수천~수만 개 만들었다 버림 → 힙이 유리 (할당 10ns)
- I/O: 소수의 버퍼를 만들어놓고 재사용 → Direct Buffer 유리 (복사 절약)

## Direct Buffer는 누가 쓰는가

java.nio.ByteBuffer는 JDK 표준 API. 아무 코드에서나 쓸 수 있지만
직접 쓸 일은 거의 없고 I/O 프레임워크들이 내부적으로 사용한다.

```
┌─────────────────────────────────────────────┐
│              JDK (java.nio)                  │
│        ByteBuffer.allocateDirect()           │
│                                               │
│  ┌───────────┐  ┌────────┐  ┌─────────────┐ │
│  │  Tomcat    │  │ Netty  │  │ MySQL JDBC  │ │
│  │  NIO 커넥터 │  │(Redis) │  │             │ │
│  └───────────┘  └────────┘  └─────────────┘ │
└─────────────────────────────────────────────┘
```

우리 코드에서는 Direct Buffer를 한 줄도 안 쓴다. 다 프레임워크가 알아서 한다.

## Direct Buffer 재사용 (풀링)

매번 새로 만들지 않고 풀링한다 (HikariCP가 DB 커넥션을 풀링하는 것과 같은 원리).

```
요청 1: Thread-1이 Buffer A를 풀에서 꺼냄
  Buffer A: [GET /api/v1/products/group ...]
  처리 완료 → buffer.clear() → 풀에 반납

요청 2: Thread-2가 Buffer A를 풀에서 꺼냄 (Thread-1이 반납했으니까)
  Buffer A: [GET /api/v1/category ...]  ← 이전 데이터 위에 덮어씀
  처리 완료 → buffer.clear() → 풀에 반납
```

clear()는 데이터를 실제로 지우지 않음. position/limit 포인터만 리셋.
다음에 write하면 0번째부터 덮어쓰니까 이전 데이터는 자연스럽게 사라짐.

동시 접근 문제: 스레드 하나가 쓰는 동안은 그 버퍼를 다른 스레드가 못 가져감.

| | DB 커넥션 풀 | Direct Buffer 풀 |
|---|---|---|
| 비싼 자원 | TCP 커넥션 | 네이티브 메모리 (시스템 콜) |
| 미리 만들기 | 서버 시작 시 N개 | 서버 시작 시 N개 |
| 꺼내 쓰기 | pool.getConnection() | pool.acquire() |
| 반납 | connection.close() (실제로 안 닫음) | buffer.clear() (실제로 안 지움) |
