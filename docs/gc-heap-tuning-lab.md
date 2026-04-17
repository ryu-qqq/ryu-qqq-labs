# GC & JIT 실험 기록

> 브랜치: `lab/gc-heap-tuning`

## GC 기본 동작

### Eden / Old Gen 구조
```
Eden:      새 객체가 생성되는 곳. 가득 차면 Minor GC 발생
Survivor:  Minor GC에서 살아남은 객체가 잠시 머무는 곳
Old Gen:   여러 번 Minor GC를 살아남은 장수 객체가 승격되는 곳
           가득 차면 Major GC (Full GC) 발생 → pause 길어짐
```

### 단명 객체 vs 장수 객체
```bash
# 단명 객체: Eden에서 생성 → Minor GC에서 즉시 회수
curl -X POST 'localhost:8080/gc/short-lived?count=500000&sizeBytes=1024'
# → 488MB 분량 생성했지만 OOM 안 남. GC가 바로 회수하니까.

# 장수 객체: 참조 유지 → GC 회수 불가 → Old Gen에 쌓임
curl -X POST 'localhost:8080/gc/long-lived?sizeMB=50&count=5'
# → Old Gen 20MB → 275MB. 참조 끊기 전까지 안 줄어듦.
```

### G1GC Humongous Object
```
Region 크기의 50% 이상인 객체 → Eden 안 거치고 바로 Old Gen 직행
50MB 객체 = Humongous → Grafana에서 Eden 안 움직이고 Old Gen만 올라감
```

### OOM 터뜨리기
```
힙 512MB에서 장수 객체 400MB+ 쌓은 후 추가 할당 시도
→ Full GC 긴급 실행 → 참조 중이라 회수 불가 → OOM: Java heap space
```

### 메모리 누수 원리
```
참조가 살아있으면 GC가 회수 못 함.
실무 패턴: static List에 add만 하고 remove 안 함, 캐시 만료 안 시킴
→ Old Gen 계속 올라감 → OOM

release() 호출 → 참조 끊음 → GC 회수 → Old Gen 급감
```

## GC 종류별 비교

### G1GC vs ZGC (pressure 테스트, 15초)
```
              G1GC              ZGC
STW pause     10~80ms           거의 0ms
throughput    1.5억 객체         6,285만 객체 (CPU를 GC가 먹어서)
CPU 사용      낮음              높음 (동시 GC)
적합한 힙     수백 MB~수 GB     수 GB~TB

G1GC = 식당 닫고 대청소 (손님 잠깐 멈춤)
ZGC  = 영업하면서 직원이 옆에서 청소 (안 멈추지만 직원 더 필요)
```

### 힙 크기별 비교 (heap-size-test, 10초)
```
힙 크기     GC 횟수     GC 총 pause    평균 pause
128MB      10,069회    4,375ms        ~0.4ms
512MB       3,310회    2,384ms        ~0.7ms
1GB         1,267회    1,330ms        ~1.0ms

힙 작으면: GC 자주 + pause 짧음 + 총 시간 많이 소비
힙 크면:   GC 덜 자주 + pause 길어질 수 있음 + 총 시간 적음
적정값:    컨테이너의 50~60%
```

### MaxGCPauseMillis
```
G1GC에게 "pause를 N ms 이하로 맞춰라"고 목표를 주는 것.
10ms vs 200ms 비교: 힙 512MB에서는 차이 미미 (이미 여유라서)
힙이 크고 Old Gen에 객체 많을 때 차이가 남.
```

## JIT 컴파일러

### 웜업 효과
```bash
curl 'localhost:8080/jit/warmup?rounds=10&iterationsPerRound=1000000'

라운드 1~5: 4,523~10,446us (인터프리터)
라운드 6:   844us (JIT 컴파일 시작)
라운드 7~:  337~339us (네이티브 코드)
→ 13.3배 빨라짐

서버 시작 직후 느린 이유가 이것. 트래픽 받으면서 JIT가 컴파일 → 빨라짐.
```

### 탈출 분석 (Escape Analysis)
```bash
curl 'localhost:8080/jit/escape-analysis?iterations=5000000'

noEscape: 4ms, GC 0회  (객체가 메서드 안에서만 사용 → 스택 할당)
escape:   43ms, GC 0회 (객체가 밖으로 나감 → 힙 할당)
→ 10배 차이

JIT가 "이 객체 밖으로 안 나가네?" → 힙에 안 만들고 스택에서 계산만.
```

### 인라이닝
```bash
curl 'localhost:8080/jit/inlining?iterations=10000000'

메서드 호출: 4ms
직접 계산:   3ms  ← 거의 동일!

JIT가 작은 메서드를 호출부에 직접 삽입 → 호출 오버헤드 0.
"성능 때문에 메서드 안 쪼개야 해" → 틀림. JIT가 알아서 인라이닝.
```

### Code Cache (C1/C2)
```bash
curl 'localhost:8080/jit/code-cache'

profiled nmethods (C1):     6/116MB (5%)  ← 빠르게 컴파일, 프로파일링용
non-profiled nmethods (C2): 3/116MB (2%)  ← 느리게 컴파일, 강한 최적화

Tiered Compilation 흐름:
  인터프리터 → C1 (빠른 컴파일) → C2 (최적화 컴파일)
  Code Cache 100% 차면 → JIT 멈춤 → 성능 저하
```

## 컨테이너 환경 JVM 설정

### Xmx vs MaxRAMPercentage
```
-Xmx512m (고정):
  컨테이너 2GB → 힙 512MB
  컨테이너 4GB → 힙 512MB (메모리 남는데 못 씀)

-XX:MaxRAMPercentage=50 (비율):
  컨테이너 2GB → 힙 1024MB (50%)
  컨테이너 4GB → 힙 2048MB (자동 스케일)
```

### 50%가 안전한 이유
```
컨테이너 2048MB:
  힙:           1024MB (50%)
  Metaspace:    ~100MB
  Code Cache:   ~80MB
  Thread Stack: ~200MB
  Direct Buffer: ~50MB
  여유:         ~594MB ← 안전

75%로 잡으면:
  힙 + Non-Heap = ~1966MB ← 2048MB에 바짝 → OOM Kill 위험
```

### UseContainerSupport
```
기본 true: JVM이 컨테이너 메모리 한도를 인식
false:     호스트 전체 메모리를 보고 힙 설정 → 컨테이너 한도 초과 → OOM Kill
```

## API 엔드포인트

### GC 실험 (`/gc/*`)
| 엔드포인트 | 설명 |
|-----------|------|
| `POST /gc/short-lived?count=500000&sizeBytes=1024` | 단명 객체 대량 생성 → Minor GC 관찰 |
| `POST /gc/long-lived?sizeMB=50&count=5` | 장수 객체 → Old Gen 채우기 |
| `POST /gc/release` | 장수 객체 해제 → Old Gen 회수 |
| `POST /gc/pressure?durationSeconds=15` | GC 극한 부하 (GC 종류별 비교용) |
| `GET /gc/status` | 현재 GC 상태, 힙, JVM 플래그 |

### JIT 실험 (`/jit/*`)
| 엔드포인트 | 설명 |
|-----------|------|
| `GET /jit/warmup?rounds=10` | JIT 웜업 효과 측정 |
| `GET /jit/escape-analysis?iterations=5000000` | 탈출 분석 효과 비교 |
| `GET /jit/inlining?iterations=10000000` | 인라이닝 효과 비교 |
| `GET /jit/code-cache` | Code Cache 상태 |
| `GET /jit/status` | JIT + GC 종합 상태 |

### G1GC 튜닝 (`/gc/tuning/*`)
| 엔드포인트 | 설명 |
|-----------|------|
| `GET /gc/tuning/config` | 현재 JVM 설정 전체 |
| `POST /gc/tuning/pause-target-test` | MaxGCPauseMillis 효과 측정 |
| `POST /gc/tuning/heap-size-test` | 힙 크기별 GC 비교 |
| `GET /gc/tuning/container-info` | 컨테이너 메모리 인식 확인 |

## GC 변경 방법

docker-compose.yml의 JAVA_OPTS 수정 후 재시작:
```bash
# G1GC (기본)
-XX:+UseG1GC

# ZGC (초저지연)
-XX:+UseZGC

# 힙 크기 변경
-Xmx128m / -Xmx512m / -Xmx1g

# MaxRAMPercentage 방식
-XX:MaxRAMPercentage=50 (Xmx 제거)

# 재시작
./gradlew bootJar && docker compose up -d --build app
```
