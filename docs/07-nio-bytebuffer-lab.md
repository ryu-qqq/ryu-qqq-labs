# 07. NIO & ByteBuffer 실험 기록

> 브랜치: `lab/nio-bytebuffer`

## 이 실험에서 배운 것

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
curl -X POST "localhost:8080/nio/buffer/1/put?data=GET%20/ap"
curl -X POST 'localhost:8080/nio/buffer/1/flip'
curl -X POST 'localhost:8080/nio/buffer/1/get?count=3'
curl -X POST 'localhost:8080/nio/buffer/1/compact'
curl -X POST "localhost:8080/nio/buffer/1/put?data=i/products"
# → " /api/products" 완성
```

### 힙 버퍼 vs 다이렉트 버퍼

| | 힙 버퍼 | 다이렉트 버퍼 |
|---|---|---|
| 할당 속도 | ~10ns (Eden bump pointer) | ~1000ns (OS 시스템 콜) |
| I/O 복사 | 2번 (커널→임시→힙) | 1번 (커널→다이렉트) |
| GC 영향 | GC가 이동시킬 수 있음 | 주소 고정, 커널 직접 접근 |
| 용도 | 비즈니스 로직 | I/O 버퍼 (재사용) |

벤치마크: clear()로 재사용이 매번 할당 대비 232배 빠름 → 풀링의 존재 이유

## Tomcat NIO 아키텍처

```
Acceptor (1개):  TCP backlog에서 커넥션 accept() → Poller한테 넘김
Poller (1~2개):  Selector로 SocketChannel 감시 → "데이터 왔다" → Worker한테 넘김
Worker (max=200): read(buffer) → flip → HTTP 파싱 → 컨트롤러 → put(응답) → flip → write → clear
```

- SocketChannel: 커널 소켓 버퍼와 연결된 통로 (JDK 표준)
- Selector: 여러 채널을 스레드 1개로 감시
- HTTP 완성 판단: `\r\n\r\n` 발견 → 헤더 끝, Content-Length로 body 크기 확인
- 크기 제한: 헤더 8KB (톰캣), body 10MB (Spring)

## Grafana 모니터링 실험

### 스레드 고갈
```bash
for i in $(seq 1 19); do curl -s "localhost:8080/nio/stress/thread-block?seconds=60" & done
```
- busy=20 찍히면 서버 장애 직전
- 20개 전부 점유하면 메트릭 수집 자체가 안 됨

### 다이렉트 버퍼 누수
```bash
curl -X POST 'localhost:8080/nio/stress/buffer-leak?sizeMB=10&count=5'  # 4→54MB 급상승
curl -X POST 'localhost:8080/nio/stress/buffer-release'                  # 54→4MB 복구
```
- MaxDirectMemorySize 초과 시 OOM: Direct buffer memory
- 한도 미설정 시 컨테이너 OOM Kill (로그 없이 죽음)

## API 엔드포인트

### ByteBuffer 실험 (`/nio/buffer/*`)
| 엔드포인트 | 설명 |
|-----------|------|
| `POST /nio/buffer/create?size=16` | 힙 버퍼 생성 |
| `POST /nio/buffer/create-direct?size=16` | 다이렉트 버퍼 생성 |
| `POST /nio/buffer/{id}/put?data=Hello` | 데이터 쓰기 |
| `POST /nio/buffer/{id}/flip` | 쓰기→읽기 전환 |
| `POST /nio/buffer/{id}/get?count=3` | 데이터 읽기 |
| `POST /nio/buffer/{id}/clear` | 리셋 |
| `POST /nio/buffer/{id}/compact` | 남은 데이터 보존하며 리셋 |
| `GET /nio/buffer/{id}/history` | 연산 히스토리 |

### 벤치마크 (`/nio/benchmark/*`)
| 엔드포인트 | 설명 |
|-----------|------|
| `GET /nio/benchmark/allocate` | 힙 vs 다이렉트 할당 속도 |
| `GET /nio/benchmark/io` | 파일 I/O 속도 |
| `GET /nio/benchmark/reuse` | 재사용 vs 매번 할당 |

### 에코서버 (`/nio/server/*`)
| 엔드포인트 | 설명 |
|-----------|------|
| `POST /nio/server/start?port=9999` | 에코서버 시작 (스레드 1개) |
| `POST /nio/server/connect` | 클라이언트 접속 |
| `POST /nio/server/send?clientId=1&msg=Hello` | 메시지 전송 + 에코 |
| `GET /nio/server/status` | 서버 상태 |
| `GET /nio/server/events` | 이벤트 로그 |

### 스트레스 테스트 (`/nio/stress/*`)
| 엔드포인트 | 설명 |
|-----------|------|
| `GET /nio/stress/thread-block?seconds=30` | 톰캣 스레드 점유 |
| `POST /nio/stress/buffer-leak?sizeMB=10&count=5` | 다이렉트 버퍼 누수 |
| `POST /nio/stress/buffer-release` | 누수 버퍼 해제 |

## Grafana 대시보드 패널

| 패널 | 주의 신호 |
|------|----------|
| Tomcat Threads | busy = max (스레드 고갈) |
| Direct Buffer Memory | 계속 상승 (누수) |
| HikariCP Connections | pending > 0 (커넥션 대기) |
| GC Pause Time | 200ms 이상 (긴 STW) |
| Heap Memory | Old Gen 계속 올라감 |
