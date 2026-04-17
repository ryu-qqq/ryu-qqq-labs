# HikariCP & 풀 고갈 실험 기록

> 브랜치: `lab/pool-exhaustion`

## HikariCP 기본 개념

### 왜 커넥션 풀이 필요한가
```
DB 커넥션 1개 만드는 비용:
  - TCP handshake:    ~3ms
  - MySQL 인증:       ~5ms
  - 세션 초기화:      ~2ms
  합계:              ~10ms

매 요청마다 새로 만들면 10ms 낭비.
미리 풀에 만들어놓고 빌려주면 <1ms.
```

### 주요 설정
```yaml
hikari:
  maximum-pool-size: 5          # 풀 최대 크기
  minimum-idle: 2                # 최소 유지 커넥션 (웜업 효과)
  connection-timeout: 1000       # 커넥션 얻기 대기 시간 (ms)
  idle-timeout: 30000            # idle 커넥션 유지 시간
  max-lifetime: 1800000          # 커넥션 최대 생명 (30분)
                                 # ★ MySQL wait_timeout보다 짧아야 함!
  keepalive-time: 300000         # idle 커넥션 주기적 ping
  leak-detection-threshold: 3000 # N ms 이상 안 반납하면 경고
```

## 실험 1: 커넥션 고갈

### 시나리오
```bash
# 5개 커넥션을 15초간 점유
curl -X POST 'localhost:8080/pool/exhaust?connections=5&holdSeconds=15'

# 다른 터미널에서 쿼리 → 대기하다 타임아웃
curl -X POST 'localhost:8080/pool/query-wait'
```

### 결과
```
connection-timeout=5000: elapsedMs=5010, 실패
connection-timeout=1000: elapsedMs=1015, 실패 (빠른 실패)

실무 가이드:
  짧게(1~2초): 빠른 실패, 연쇄 장애 방지
  길게(10초+): 일시 폭주 견딤, but 스레드 오래 점유
```

## 실험 2: 스레드풀 + 커넥션풀 조합

### 시나리오 A: 스레드 20 + 커넥션 5
```bash
for i in $(seq 1 20); do curl -s 'localhost:8080/pool/combined-stress?holdMs=3000' & done
```

```
Tomcat Threads: current=20, busy=5 (5만 진짜 일함)
HikariCP:       active=5, pending 치솟음
→ 커넥션 병목
```

### 시나리오 B: 스레드 5 + 커넥션 20
```
Tomcat Threads: current=5, busy=5 (전부 일함)
HikariCP:       active=5, idle=15 (커넥션 놀고 있음)
→ 스레드 병목, 커넥션 낭비
```

### 실무 적정 비율
```
Tomcat max = HikariCP max × 2~4배

이유: 모든 요청이 DB 쓰는 건 아님
      (캐시 히트, 정적 파일 등은 커넥션 불필요)

주의: DB 서버의 max_connections 확인
      (MySQL 기본 151. 서버 × max 합이 안에 들어와야)
```

## 실험 3: Connection Leak Detection

### Leak 재현
```java
// 잘못된 코드 (leak 발생)
Connection conn = dataSource.getConnection();
// conn.close() 호출 안 함 → leak

// 올바른 코드
try (Connection conn = dataSource.getConnection()) {
    // ...
} // 자동 close
```

### HikariCP 자동 감지
```bash
curl -X POST 'localhost:8080/pool/leak?count=2'
# 3초 후 로그:
# WARN [pool housekeeper] ProxyLeakTask :
#   Connection leak detection triggered ... stack trace follows
#   java.lang.Exception: Apparent connection leak detected
```

**스택 트레이스에 leak 발생 코드 위치가 찍힘** → 프로덕션에서 leak 감지 필수.

## 실험 4: MySQL wait_timeout 미스매치

### 문제 상황
```
MySQL wait_timeout: 기본 28800초 (8시간)
HikariCP maxLifetime: 기본 30분

DBA가 wait_timeout을 짧게 바꾸면:
  MySQL: 짧은 시간 idle이면 커넥션 끊음
  HikariCP: 풀에서 빌려줄 때 끊긴 커넥션
  → "Communications link failure"
```

### HikariCP 방어 메커니즘
```
1. maxLifetime:    주기적 커넥션 교체
2. keepaliveTime:  주기적 idle 커넥션 ping
3. isValid() 체크: 빌려줄 때 검증 (기본 동작)

원칙: maxLifetime < wait_timeout
```

### 실험
```bash
# root로 MySQL wait_timeout을 10초로 설정
docker compose exec mysql mysql -uroot -plab1234 \
  -e "SET GLOBAL wait_timeout = 10;"

# 15초 idle 후 쿼리
curl -X POST 'localhost:8080/pool/test-stale-connection?idleSeconds=15'
# → HikariCP가 자동 검증 + 재생성 → 성공
```

## 실험 5: Pool Warming

### 콜드 풀 문제
```
서버 시작 직후:
  min-idle=2만큼만 생성 → 풀에 2개
  첫 트래픽 10개 → 8개는 새로 만들어야 함 (10ms×8 = 80ms 지연)
```

### Warming 효과
```bash
# 풀을 max까지 채우기
curl -X POST 'localhost:8080/pool/warm-up'
# elapsed: 78ms (숨어있던 비용)

# acquire-time 측정
curl 'localhost:8080/pool/acquire-time?samples=5'
```

```
Before warm-up: 평균 309us, 최대 1,373us
After warm-up:  평균 127us, 최대 424us
→ 2.4배 빠름
```

### 실전 가이드
```
1. minimum-idle을 적정 수준으로 설정
2. 배포 직후 워밍업 요청 날리기 (ALB 헬스체크 + 실제 쿼리)
3. Blue-Green / Canary 배포로 자연 예열
4. HikariCP total < max + pending > 0 알림
```

## 실험 6: DB 재시작 시 풀 복구

### 시나리오
```bash
# 재시작 전
curl localhost:8080/pool/status  # total=2, idle=2
curl -X POST localhost:8080/pool/query-wait  # 성공 24ms

# MySQL 재시작 (커넥션 전부 끊김)
docker compose restart mysql

# 재시작 후 쿼리
curl -X POST localhost:8080/pool/query-wait  # 성공 45ms (살짝 느림)

# 상태 확인
curl localhost:8080/pool/status  # total=2, idle=2 (자동 복구!)
```

### HikariCP 자동 복구 과정
```
1. query-wait 요청
2. 죽은 커넥션 감지 (isValid 실패)
3. 폐기 → 새 커넥션 생성
4. 쿼리 성공
5. housekeeper가 min-idle=2 맞추려 추가 생성
6. 완전 복구

로그:
  WARN ... Failed to validate connection
  "No operations allowed after connection closed"
  "Possibly consider using a shorter maxLifetime value"
```

### 실무 의미
```
일반 풀: DB 재시작 → 앱 수동 재시작 필요
HikariCP: 첫 요청 살짝 느림 → 자동 복구
         사용자: "일시적으로 느렸다" 정도
```

## API 엔드포인트

### 풀 상태 (`/pool/*`)
| 엔드포인트 | 설명 |
|-----------|------|
| `GET /pool/status` | 풀 설정 + 현재 상태 |
| `POST /pool/exhaust?connections=5&holdSeconds=15` | 커넥션 N개 점유 |
| `POST /pool/query-wait` | 단순 쿼리 (대기 시간 측정) |
| `GET /pool/combined-stress?holdMs=3000` | 스레드+커넥션 조합 스트레스 |
| `POST /pool/leak?count=2` | Leak 시뮬레이션 (close 안 함) |
| `POST /pool/leak-fix` | Leak 된 커넥션 수동 반납 |
| `POST /pool/warm-up` | 풀을 max까지 채우기 |
| `GET /pool/acquire-time?samples=5` | 커넥션 획득 시간 측정 |
| `POST /pool/test-stale-connection?idleSeconds=15` | stale 커넥션 테스트 |

### MySQL 조작
```bash
# wait_timeout 설정
docker compose exec mysql mysql -uroot -plab1234 \
  -e "SET GLOBAL wait_timeout = 10;"

# 원복
docker compose exec mysql mysql -uroot -plab1234 \
  -e "SET GLOBAL wait_timeout = 28800;"

# MySQL 재시작
docker compose restart mysql
```

## Grafana 관찰 포인트

| 패널 | 주의 신호 |
|------|----------|
| HikariCP Connections (active/idle/pending) | pending > 0 (풀 부족) |
| HikariCP Acquire Time | 5초 치솟음 (timeout 발생) |
| Tomcat Threads | busy = max + HikariCP pending 있으면 커넥션 병목 |
| Tomcat Threads | busy = max + HikariCP idle 많으면 스레드 병목 |
