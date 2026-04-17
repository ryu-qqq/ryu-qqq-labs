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
| `lab/nio-bytebuffer` | NIO & ByteBuffer | ByteBuffer 사이클, Tomcat NIO 구조 (Acceptor→Selector→Worker), Selector 에코서버, 스레드 고갈/버퍼 누수 실험 | [nio-bytebuffer-lab.md](docs/nio-bytebuffer-lab.md) |
