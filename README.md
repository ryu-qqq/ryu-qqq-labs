# Lab

직접 코드를 만들고 부숴보면서 동작 원리를 학습하는 실험 프로젝트.

## 실행 방법

```bash
# 전체 모니터링 스택 (Grafana + Prometheus + MySQL + Redis)
./gradlew bootJar && docker compose up -d --build
# Grafana: http://localhost:3000 (admin/admin)
# App:     http://localhost:8080
```

## 실험 브랜치

각 실험은 독립 브랜치에서 진행. 서로 코드가 섞이지 않게 분리.

| 브랜치 | 주제 | 핵심 내용 | 문서 |
|--------|------|----------|------|
| `lab/nio-bytebuffer` | NIO & ByteBuffer | ByteBuffer 사이클, Tomcat NIO 구조 (Acceptor→Selector→Worker), Selector 에코서버, 스레드 고갈/버퍼 누수 실험 | [07-nio-bytebuffer-lab.md](docs/07-nio-bytebuffer-lab.md) |

## 학습 문서

| # | 문서 | 내용 |
|---|------|------|
| 01 | [JVM 메모리 구조](docs/01-jvm-memory-structure.md) | 힙, Metaspace, Code Cache, Thread Stack, Direct Buffer |
| 02 | [Direct Buffer 심화](docs/02-direct-buffer-deep-dive.md) | 힙 vs 다이렉트, 복사 횟수, 풀링 원리 |
| 03 | [풀은 서로 독립적이다](docs/03-pools-are-independent.md) | 스레드풀, 버퍼풀, 커넥션풀의 관계 |
| 04 | [GC와 힙 튜닝](docs/04-gc-and-heap-tuning.md) | MaxRAMPercentage, OOM Kill, GC Pause |
| 07 | [NIO & ByteBuffer 실험](docs/07-nio-bytebuffer-lab.md) | ByteBuffer, Tomcat NIO, Selector, 스트레스 테스트 |
