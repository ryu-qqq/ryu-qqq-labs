# MySQL 성능 튜닝 실험 기록

> 브랜치: `lab/mysql-performance`

## 실험 1: Bulk Insert

### 문제
```
save() for-loop 패턴: INSERT 한 번에 1행씩
매 INSERT = 개별 트랜잭션 = 디스크 fsync
매 INSERT = 네트워크 왕복
→ 극도로 느림
```

### 실험 결과
```
naive (execute 1번씩, autoCommit=true):
  rows=1,000, elapsed=949ms, TPS=1,053

batch (addBatch + executeBatch, 1 트랜잭션):
  rows=100,000, elapsed=222ms, TPS=450,450

→ 100배 많은 데이터를 4배 빠르게
→ 실질 TPS 450배 개선
```

### JDBC URL 옵션
```
rewriteBatchedStatements=true
  JDBC가 INSERT N개 → INSERT VALUES (...), (...), ... 로 재작성
  네트워크 왕복 감소
  Docker 로컬에선 1.2배지만 RDS 같은 원격은 10~100배
```

### 포트폴리오 스토리
```
"배치 INSERT 최적화로 처리량 450배 향상
 (TPS 1천 → 45만)
 - save() for-loop → saveAll()/batchUpdate
 - @Transactional로 묶기
 - rewriteBatchedStatements=true"
```

## 실험 2: innodb_flush_log_at_trx_commit

### MySQL이 파일에 쓸 때 일어나는 일
```
앱 메모리 (MySQL Buffer Pool)
  ↓ write()
OS 커널 Page Cache (커널 공간)
  ↓ fsync() ← 느림!
디스크 (물리 저장소)
```

### 값별 동작
```
값 1 (기본, ACID 완벽):
  COMMIT마다 Page Cache + fsync까지
  크래시 시 손실 0

값 2:
  COMMIT마다 Page Cache까지만
  MySQL 크래시 OK (OS가 flush)
  서버 크래시 시 최대 1초 손실

값 0:
  1초마다 한번에 write + fsync
  어떤 크래시든 최대 1초 손실
```

### 실험 결과 (1,000행 naive insert)
```
값 1:  842ms  (baseline)
값 2:  110ms  (7.7배)
값 0:   64ms  (13배)
```

### 실무 현실
```
⚠️ 값 2/0은 함부로 못 씀

TPS 10만인 시스템에서 1초 손실 = 10만 건 소실
사용자 데이터면 절대 불가

실제 해결책:
  - 하드웨어 (NVMe SSD)로 fsync 자체를 빠르게
  - 애플리케이션 레이어 batch + @Transactional
  - Kafka로 비동기 파이프라인
  
값 2/0이 쓰이는 곳:
  staging/dev, 이미 Kafka로 이중 저장된 조회 전용 DB
```

## 실험 3: Prepared Statement Caching

### 캐시 위치
```
클라이언트 사이드 (JDBC 드라이버):
  cachePrepStmts=true
  저장: JVM 프로세스 메모리
  단위: 커넥션당 (LRU 크기 기반, 기본 25, 권장 250)

서버 사이드 (MySQL):
  useServerPrepStmts=true
  저장: MySQL 서버 메모리
  단위: 세션(커넥션)당
  세션 종료 시 자동 해제
```

### 실험 결과 (10,000회 SELECT 반복)
```
캐시 없음:
  Com_stmt_prepare: 10,000 (매번 PREPARE)
  elapsed: 644ms

캐시 있음:
  Com_stmt_prepare: 1 (첫 번째만)
  Com_stmt_execute: 10,000
  elapsed: 549ms

→ 속도 1.2배, 하지만 MySQL 서버 CPU 크게 절약
```

### 권장 JDBC URL
```
?useServerPrepStmts=true
&cachePrepStmts=true
&prepStmtCacheSize=250
&prepStmtCacheSqlLimit=2048
```

### 확인 방법
```sql
SHOW GLOBAL STATUS LIKE 'Com_stmt_%';
SHOW GLOBAL STATUS LIKE 'Prepared_stmt_count';
```

## 실험 4: Buffer Pool Hit Rate

### Hit Rate = 1 - (reads / read_requests)
```
Innodb_buffer_pool_read_requests: 전체 조회 (logical)
Innodb_buffer_pool_reads:          디스크까지 간 조회 (physical)

99%+: 건강
95~99%: 메모리 부족 조짐
95% 이하: 심각, 즉시 조치 필요
```

### 실험 결과 (500,000행, 131MB 테이블)
```
환경                    elapsedMs   hitRate   diskReads
────────────────────────────────────────────────────────
128MB 콜드              142         97.23%    1,688
128MB 웜                108         98.58%      854
256MB + 웜업 후          96         99.16%      501

→ Hit Rate 1.93%p 상승 = 응답시간 32% 개선
→ 디스크 I/O 70% 감소
```

### 실무 모니터링
```sql
SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_%';
SHOW ENGINE INNODB STATUS;
```

### 튜닝 가이드
```
innodb_buffer_pool_size = 물리 메모리의 70~80%
  (MySQL 전용 서버 기준)

RDS의 경우:
  db.t3.small:  100%, 약 1.5GB
  db.m5.xlarge: 16GB × 70% = 약 11GB
```

## 실험 5: Slow Query Log

### 활성화
```sql
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 0.1;  -- 100ms 이상 기록
SET GLOBAL log_queries_not_using_indexes = 'ON';  -- 인덱스 안 타는 쿼리도 기록
```

### 로그 파일 형식
```
# Query_time: 0.383283     ← 쿼리 실행 시간 (초)
# Lock_time: 0.000014      ← 락 대기 시간
# Rows_sent: 1             ← 결과로 보낸 행 수
# Rows_examined: 500000    ← 실제 검사한 행 수 ★
SELECT COUNT(*) FROM big_table WHERE data LIKE '%xxx%';
```

### 범인 신호
```
Rows_examined >> Rows_sent
  → 필요 없는 행을 많이 검사
  → 인덱스 없거나 잘못된 쿼리

예: Rows_examined=500,000, Rows_sent=1
    → 50만 행을 뒤져서 1개 리턴 → 인덱스 필요
```

### 실험한 나쁜 패턴들
```
1. N+1 쿼리 (21번 실행):  12ms  vs  IN 쿼리 1번: 4ms (3배)
2. UPPER(column) LIKE:    415ms (인덱스 무력화)
3. LIKE '%xxx':           293ms (선행 와일드카드로 인덱스 무력화)
4. 인덱스 없는 LIKE:      389ms (풀스캔)
```

### 실무 활용
```
1. pt-query-digest로 집계
   → "가장 오래 걸리는 TOP 10"
   → "가장 많이 호출되는 느린 쿼리"

2. EXPLAIN으로 실행계획 분석
   type: ALL (풀스캔) → index 필요
   key: NULL (인덱스 안 탐)
   rows: 많을수록 비효율

3. 해결:
   - 적절한 인덱스 추가
   - N+1 제거 (fetch join, @BatchSize)
   - LIKE '%xxx%' → Full-Text Index or Elasticsearch
```

## API 엔드포인트

### Bulk Insert (`/mysql/bulk/*`)
| 엔드포인트 | 설명 |
|-----------|------|
| `POST /mysql/bulk/benchmark?rows=10000` | executeBatch on/off 비교 |
| `POST /mysql/bulk/naive?rows=1000` | 최악: execute 1번씩 + autoCommit |

### Prepared Statement Cache (`/mysql/prepstmt/*`)
| 엔드포인트 | 설명 |
|-----------|------|
| `POST /mysql/prepstmt/benchmark?iterations=10000` | 캐시 on/off 비교 |
| `GET /mysql/prepstmt/server-stats` | MySQL 서버의 prepare/execute 카운터 |

### Buffer Pool (`/mysql/buffer/*`)
| 엔드포인트 | 설명 |
|-----------|------|
| `GET /mysql/buffer/status` | 현재 Hit Rate, 캐시 사용량 |
| `POST /mysql/buffer/setup?rows=500000` | 큰 테이블 생성 |
| `POST /mysql/buffer/full-scan` | 풀 스캔 (캐시 올리기) |
| `POST /mysql/buffer/random-read?queries=1000` | 랜덤 조회 (miss 유도) |

### Slow Query (`/mysql/slow/*`)
| 엔드포인트 | 설명 |
|-----------|------|
| `GET /mysql/slow/status` | Slow Query Log 설정 확인 |
| `POST /mysql/slow/sleep?seconds=1` | 인위적 지연 |
| `POST /mysql/slow/full-scan` | 인덱스 없는 LIKE |
| `POST /mysql/slow/n-plus-one?n=20` | N+1 쿼리 |
| `POST /mysql/slow/batch-fetch?n=20` | IN 쿼리 (개선) |
| `POST /mysql/slow/bad-patterns` | 함수 WHERE, 선행 와일드카드 |

## MySQL 조작 명령

### 설정 변경 (GLOBAL)
```bash
# Buffer Pool 크기
docker compose exec mysql mysql -uroot -plab1234 -e \
  "SET GLOBAL innodb_buffer_pool_size = 268435456;"  # 256MB

# flush_log (내구성 vs 성능)
docker compose exec mysql mysql -uroot -plab1234 -e \
  "SET GLOBAL innodb_flush_log_at_trx_commit = 2;"

# Slow Query Log
docker compose exec mysql mysql -uroot -plab1234 -e \
  "SET GLOBAL slow_query_log='ON';
   SET GLOBAL long_query_time=0.1;
   SET GLOBAL log_queries_not_using_indexes='ON';"

# Slow Query Log 확인
docker compose exec mysql sh -c 'tail -80 /var/lib/mysql/*-slow.log'

# MySQL 재시작 (콜드 상태 만들기)
docker compose restart mysql
```
