# 03. 스레드풀, 버퍼풀, 커넥션풀은 별개다

## 핵심: 1:1 관계가 아니다

```
스레드 풀:  "일하는 사람" 200명을 미리 고용
            각자 1MB 스택 가짐 → 200MB (Non-Heap)
            
버퍼 풀:    "공용 화이트보드" N개를 미리 준비
            일하는 사람이 필요할 때 빌려서 쓰고 반납
            → ~50MB (Non-Heap)
            
커넥션 풀:  "전화기" 20대를 미리 설치 (DB)
            전화할 때마다 새로 개통하면 느리니까
            → 커넥션당 버퍼가 딸려있음

1:1 관계가 아님:
  스레드 200개가 전부 동시에 DB 전화를 하진 않음
  전화기(커넥션) 20대를 돌려씀
  화이트보드(버퍼)도 돌려씀
```

## 각 프레임워크별 버퍼 관리

### Tomcat (HTTP)

NIO의 핵심: 스레드 1개가 여러 커넥션을 처리할 수 있음

```
Poller 스레드 (1~2개): 어떤 커넥션에 데이터가 왔는지 감시만 (select/epoll)
Worker 스레드 풀 (200개): 실제 요청 처리
```

버퍼는 "커넥션"당 붙는다. 스레드당이 아님.
커넥션 500개가 연결돼도 데이터 보내는 게 30개면 버퍼 30개만 활성화.

→ Tomcat Direct Buffer: ~1MB (매우 적음)

### MySQL JDBC (HikariCP)

커넥션당 버퍼를 가진다.

```
커넥션 풀 20개 × 커넥션당 ~1MB = ~20MB
실제 Direct Buffer는 ~5~10MB (힙 버퍼와 섞어 씀)
```

### Lettuce/Netty (Redis)

Netty가 가장 정교한 버퍼 풀링 (PooledByteBufAllocator).
- 청크(Chunk) 단위로 큰 메모리를 미리 할당 (16MB 단위)
- Arena라는 영역으로 스레드 경합을 줄임
- 실제 사용량은 Redis 커넥션 수에 비례: ~8MB

### 합산

```
Tomcat NIO:        ~1MB
MySQL JDBC:        ~10MB
Netty (Redis):     ~8MB
Netty 풀 오버헤드:  ~30MB (미리 할당한 청크)
합계:              ~50MB
```

## Direct Buffer 한도

설정 안 하면 기본값 = MaxHeapSize와 같음.
초과하면: java.lang.OutOfMemoryError: Direct buffer memory
