# 07. NIO & ByteBuffer 실험 기록

## 이 브랜치에서 배운 것 (lab/nio-bytebuffer)

### ByteBuffer란?
고정 크기 byte 배열 + 읽기/쓰기 위치를 추적하는 포인터 3개.

```
position: 지금 여기서 읽거나 쓴다 (펜 위치)
limit:    여기까지만 읽거나 쓸 수 있다
capacity: 전체 크기 (절대 안 변함)
```

### 핵심 연산 5개

```
put(data)    → 데이터 쓰기. position이 쓴 만큼 전진
flip()       → 쓰기→읽기 전환. limit=position, position=0
get(count)   → 데이터 읽기. position이 읽은 만큼 전진
clear()      → 전부 리셋. position=0, limit=capacity. 데이터는 안 지움!
compact()    → 안 읽은 데이터만 앞으로 밀고 리셋. 부분 읽기 후 이어쓸 때
```

### 실험으로 확인한 사이클

```bash
# 1. 버퍼 생성 (16바이트 빈 종이)
curl -X POST 'localhost:8080/nio/buffer/create?size=16'

# 2. 데이터 쓰기 ("Hi" → position 0→2)
curl -X POST 'localhost:8080/nio/buffer/1/put?data=Hi'

# 3. 읽기 모드 전환 (position=0, limit=2)
curl -X POST 'localhost:8080/nio/buffer/1/flip'

# 4. 데이터 읽기 (readData="Hi", position 0→2)
curl -X POST 'localhost:8080/nio/buffer/1/get?count=2'

# 5. 리셋 (position=0, limit=16. 데이터 안 지움)
curl -X POST 'localhost:8080/nio/buffer/1/clear'
```

compact()는 네트워크에서 데이터가 쪼개져 올 때 필요:
```bash
# "GET /ap" 까지만 옴 → 일부 읽음 → compact로 남은 거 보존 → 나머지 도착
curl -X POST "localhost:8080/nio/buffer/1/put?data=GET%20/ap"
curl -X POST 'localhost:8080/nio/buffer/1/flip'
curl -X POST 'localhost:8080/nio/buffer/1/get?count=3'      # "GET" 읽음
curl -X POST 'localhost:8080/nio/buffer/1/compact'           # " /ap" 앞으로 밀기
curl -X POST "localhost:8080/nio/buffer/1/put?data=i/products"  # 나머지 도착
# → " /api/products" 완성
```

### 힙 버퍼 vs 다이렉트 버퍼

```
ByteBuffer.allocate(size)        → 힙 버퍼 (JVM 힙 안, GC 대상)
ByteBuffer.allocateDirect(size)  → 다이렉트 버퍼 (힙 바깥, GC 안 건드림)
```

| | 힙 버퍼 | 다이렉트 버퍼 |
|---|---|---|
| 할당 속도 | ~10ns (Eden bump pointer) | ~1000ns (OS 시스템 콜) |
| I/O 복사 | 2번 (커널→임시→힙) | 1번 (커널→다이렉트) |
| GC 영향 | GC가 이동시킬 수 있음 | 주소 고정, 커널 직접 접근 |
| 용도 | 비즈니스 로직 (만들고 버리는 객체) | I/O 버퍼 (만들어놓고 재사용) |

벤치마크 결과:
```bash
# 할당 속도 비교
curl 'localhost:8080/nio/benchmark/allocate'
# → 다이렉트가 1.5배 느림

# 재사용 vs 매번 할당
curl 'localhost:8080/nio/benchmark/reuse'
# → clear()로 재사용이 232배 빠름 ← 풀링의 존재 이유
```

## Tomcat NIO 아키텍처

### 스레드 3종류

```
Acceptor (1개):  커널 TCP backlog에서 완성된 커넥션을 accept()
                 → SocketChannel 생성 → Poller한테 넘김
                 → 역할 끝, 다시 대기

Poller (1~2개):  Selector를 들고 수백~수천 SocketChannel 감시
                 → "이 채널에 데이터 왔다!" 감지
                 → Worker한테 넘김 (신호만, 데이터 안 읽음)

Worker (스레드풀, max=200):
                 socketChannel.read(buffer)   ← 커널→버퍼 복사
                 buffer.flip()                ← 읽기 모드
                 HTTP 파싱                     ← "GET /api/products"
                 컨트롤러 호출                  ← 니 코드 실행
                 buffer에 응답 작성             ← put()
                 buffer.flip()                ← 다시 읽기 모드
                 socketChannel.write(buffer)   ← 버퍼→커널 복사
                 buffer.clear()               ← 재사용
```

### 핵심 개념들

**SocketChannel**: 커널 소켓 버퍼와 연결된 통로. JDK 표준 클래스.
- `channel.read(buffer)` → 커널에서 버퍼로 (요청 받기)
- `channel.write(buffer)` → 버퍼에서 커널로 (응답 보내기)

**Selector**: 여러 채널을 스레드 1개로 감시하는 장치.
- BIO: 커넥션 1000개 = 스레드 1000개 (대부분 대기)
- NIO: 커넥션 1000개 = Poller 1개가 감시, Worker 200개로 처리

**논블로킹**: channel.read()가 데이터 없으면 즉시 0 리턴하고 넘어감.
  Selector와 논블로킹은 세트. 하나라도 빠지면 의미 없음.

### HTTP 요청/응답 처리 흐름

요청이 완성됐는지 판단하는 기준:
1. `\r\n\r\n` 발견 → 헤더 끝
2. `Content-Length` 확인 → body 크기
3. body 다 모이면 → 파싱 시작

큰 요청/응답은 8KB ByteBuffer를 반복 사용:
- InputStream (요청 읽기): channel.read(buffer) → flip → get 반복
- OutputStream (응답 쓰기): buffer.put → flip → channel.write 반복
- 둘 다 ByteBuffer 사이클을 감싸는 껍데기

### 쿼리스트링/POST body 크기 제한

```
GET 쿼리스트링 짤림:  maxHttpHeaderSize = 8KB (톰캣 설정)
POST body 제한:     max-request-size = 10MB (Spring 설정)
```

## NIO Selector 미니 서버 실험

Tomcat의 Acceptor→Poller→Worker를 1개 스레드로 압축한 에코 서버.

```bash
# 서버 시작 (스레드 1개)
curl -X POST 'localhost:8080/nio/server/start?port=9999'

# 클라이언트 3개 접속
curl -X POST 'localhost:8080/nio/server/connect'
curl -X POST 'localhost:8080/nio/server/connect'
curl -X POST 'localhost:8080/nio/server/connect'

# 상태 확인: 스레드 1개가 채널 3개 감시 중
curl 'localhost:8080/nio/server/status'

# 각각 메시지 보내기
curl -X POST 'localhost:8080/nio/server/send?clientId=1&msg=Hello'
curl -X POST 'localhost:8080/nio/server/send?clientId=2&msg=World'

# 이벤트 로그: thread가 전부 "nio-echo-server" 1개
curl 'localhost:8080/nio/server/events'

# 서버 종료
curl -X POST 'localhost:8080/nio/server/stop'
```

## Grafana 모니터링 실험

### 스레드 고갈 실험
```bash
# Worker 19개를 60초간 점유 (1개는 메트릭 수집용으로 남겨야 함!)
for i in $(seq 1 19); do curl -s "localhost:8080/nio/stress/thread-block?seconds=60" & done
```
Grafana Tomcat Threads: busy=20 (19+메트릭수집1), current 치솟음
- 20개 전부 점유하면 메트릭 수집 자체가 안 됨 (Prometheus도 Worker 필요)
- busy = max 이면 서버 장애 직전

### 다이렉트 버퍼 누수 실험
```bash
# 10MB x 5개 = 50MB 할당하고 안 반납 (누수)
curl -X POST 'localhost:8080/nio/stress/buffer-leak?sizeMB=10&count=5'
# → Direct Buffer Memory: 4MB → 54MB 급상승

# 해제
curl -X POST 'localhost:8080/nio/stress/buffer-release'
# → 54MB → 4MB 복구 (GC Cleaner가 해제)
```
- 계속 올라가는 패턴 = 누수
- MaxDirectMemorySize 초과 시 OOM: Direct buffer memory
- 한도 미설정 시 컨테이너 OOM Kill (로그 없이 죽음)

## 기타 개념

**데몬 스레드**: `setDaemon(true)`. JVM 종료 시 같이 죽는 스레드.
  톰캣 스레드, 우리 에코서버 스레드 전부 데몬.
  Grafana JVM Threads: daemon = 데몬 스레드 수.

**그레이스풀 셧다운**: 데몬의 "그냥 죽음"을 보완.
  종료 시 처리 중인 요청은 마무리하고 죽게 함.
  `server.shutdown: graceful` (프로덕션 필수)

**MaxDirectMemorySize**: 다이렉트 버퍼 전체 한도.
  미설정 시 기본값 = 힙 크기. 반드시 설정할 것.
  docker-compose: `-XX:MaxDirectMemorySize=256m`
