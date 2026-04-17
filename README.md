# Lab

직접 코드를 만들고 부숴보면서 동작 원리를 학습하는 실험 프로젝트.
setof-commerce 프로덕션 서버의 메모리/성능 이슈를 분석하다가 깊이 공부하기 위해 만들었다.

## 실행 방법

```bash
# NIO 실험만 (DB/Redis 필요 없음)
./gradlew bootRun --args='--spring.profiles.active=nio'

# 풀 고갈 실험 (H2 + 로컬 Redis)
./gradlew bootRun

# 전체 모니터링 스택 (Grafana + Prometheus + MySQL + Redis)
./gradlew bootJar && docker compose up -d --build
# Grafana: http://localhost:3000 (admin/admin)
# App:     http://localhost:8080
```

## 학습 브랜치

각 실험은 독립 브랜치에서 진행. 서로 코드가 섞이지 않게 분리.

| 브랜치 | 주제 | 문서 |
|--------|------|------|
| `lab/nio-bytebuffer` | NIO, ByteBuffer, Tomcat 내부 구조 | [07-nio-bytebuffer-lab.md](docs/07-nio-bytebuffer-lab.md) |

## 학습 문서

### JVM 메모리 기초
| # | 문서 | 내용 |
|---|------|------|
| 01 | [JVM 메모리 구조](docs/01-jvm-memory-structure.md) | 힙, Metaspace, Code Cache, Thread Stack, Direct Buffer 전체 구조 |
| 02 | [Direct Buffer 심화](docs/02-direct-buffer-deep-dive.md) | 힙 버퍼 vs 다이렉트 버퍼, 복사 횟수 차이, 풀링 원리 |
| 03 | [풀은 서로 독립적이다](docs/03-pools-are-independent.md) | 스레드풀, 버퍼풀, 커넥션풀의 관계와 각 프레임워크별 버퍼 관리 |
| 04 | [GC와 힙 튜닝](docs/04-gc-and-heap-tuning.md) | MaxRAMPercentage, JVM OOM vs 컨테이너 OOM Kill, GC Pause |

### 실전 분석
| # | 문서 | 내용 |
|---|------|------|
| 05 | [setof-commerce 성능 분석](docs/05-setof-commerce-analysis.md) | Prod 서버 측정, N+1 쿼리, HikariCP 튜닝, ECS 메모리 변경 |
| 06 | [다음 실험 주제](docs/06-next-topics.md) | 풀 고갈 실험, GC 관찰, 조합 실험 등 계획 |

### 실험 기록
| # | 문서 | 내용 |
|---|------|------|
| 07 | [NIO & ByteBuffer 실험](docs/07-nio-bytebuffer-lab.md) | ByteBuffer 사이클, Tomcat NIO 구조, Selector 에코서버, 스레드 고갈/버퍼 누수 실험 |

## API 엔드포인트

### NIO ByteBuffer 실험 (`/nio/buffer/*`)
| 엔드포인트 | 설명 |
|-----------|------|
| `POST /nio/buffer/create?size=16` | 힙 버퍼 생성 |
| `POST /nio/buffer/create-direct?size=16` | 다이렉트 버퍼 생성 |
| `POST /nio/buffer/{id}/put?data=Hello` | 데이터 쓰기 (position 전진) |
| `POST /nio/buffer/{id}/flip` | 쓰기→읽기 전환 |
| `POST /nio/buffer/{id}/get?count=3` | 데이터 읽기 (position 전진) |
| `POST /nio/buffer/{id}/clear` | 리셋 (데이터 안 지움, 포인터만 초기화) |
| `POST /nio/buffer/{id}/compact` | 안 읽은 데이터 보존하며 리셋 |
| `POST /nio/buffer/{id}/rewind` | position만 0으로 (재읽기) |
| `GET /nio/buffer/{id}/history` | 연산 히스토리 전체 |

### NIO 벤치마크 (`/nio/benchmark/*`)
| 엔드포인트 | 설명 |
|-----------|------|
| `GET /nio/benchmark/allocate` | 힙 vs 다이렉트 할당 속도 비교 |
| `GET /nio/benchmark/io` | 파일 I/O 속도 비교 |
| `GET /nio/benchmark/reuse` | 재사용 vs 매번 할당 비교 |

### NIO 에코서버 (`/nio/server/*`)
| 엔드포인트 | 설명 |
|-----------|------|
| `POST /nio/server/start?port=9999` | 에코서버 시작 (스레드 1개) |
| `POST /nio/server/connect` | 클라이언트 접속 (TCP handshake) |
| `POST /nio/server/send?clientId=1&msg=Hello` | 메시지 전송 + 에코 수신 |
| `GET /nio/server/status` | 서버 상태 (스레드 수, 채널 수) |
| `GET /nio/server/events` | 이벤트 로그 (accept, echo, disconnect) |
| `POST /nio/server/disconnect?clientId=1` | 클라이언트 연결 끊기 |
| `POST /nio/server/stop` | 서버 종료 |

### 스트레스 테스트 (`/nio/stress/*`)
| 엔드포인트 | 설명 |
|-----------|------|
| `GET /nio/stress/thread-block?seconds=30` | 톰캣 Worker 스레드 점유 (동시에 여러 번 호출) |
| `GET /nio/stress/thread-status` | 톰캣 스레드 상태 확인 |
| `POST /nio/stress/buffer-leak?sizeMB=10&count=5` | 다이렉트 버퍼 누수 시뮬레이션 |
| `POST /nio/stress/buffer-release` | 누수 버퍼 해제 |
| `GET /nio/stress/buffer-status` | 누수 버퍼 현황 |

## Grafana 대시보드

http://localhost:3000 (admin/admin)

| 패널 | 모니터링 대상 | 주의 신호 |
|------|-------------|----------|
| Heap Memory Used | Eden, Old Gen, Survivor | Old Gen이 계속 올라감 |
| Non-Heap Memory Used | Metaspace, Code Cache | Metaspace 무한 증가 |
| Tomcat Threads | current, busy, max | busy = max (스레드 고갈) |
| JVM Threads | live, peak, daemon | live 급증 |
| HikariCP Connections | active, idle, pending | pending > 0 (커넥션 대기) |
| Direct Buffer Memory | used, capacity | 계속 상승 (누수) |
| Direct Buffer Count | direct, mapped | 카운트 무한 증가 |
| GC Pause Time | pause (ms) | 200ms 이상 (긴 STW) |
| GC Pause Count | count/sec | 빈도 급증 |
