# 06. 다음에 공부할 주제들

## 이 프로젝트로 실험할 것

### 실험 1: DB 커넥션 풀 고갈
- application.yml에서 hikari.maximum-pool-size를 2~20으로 바꿔보기
- /load/db-exhaust로 커넥션 점유 후 /load/db-query의 대기 시간 관찰
- waiting_threads가 0 이상이면 풀 부족이라는 걸 눈으로 확인

### 실험 2: Tomcat 스레드 풀 고갈
- tomcat.threads.max를 5~200으로 바꿔보기
- /load/thread-block으로 스레드 점유 후 다른 API 호출이 멈추는 시점 관찰
- accept-count(대기큐)의 역할 이해

### 실험 3: Direct Buffer 직접 할당/해제
- /load/direct-buffer로 할당 후 /monitor/buffers에서 증가 확인
- clear 후 GC Cleaner가 언제 해제하는지 관찰 (즉시 안 줄어듦)
- 힙 메모리와 달리 GC가 바로 회수 못하는 이유 체감

### 실험 4: 힙 채우기 + GC 관찰
- /load/heap-fill로 힙을 점점 채우면서 GC 발생 시점 관찰
- -Xmx256m으로 시작하면 GC가 빨리 발생해서 관찰 쉬움
- heap-clear 후 GC 회수 → used_MB 감소 확인

### 실험 5: 스레드풀/커넥션풀/버퍼풀 조합
- 스레드 200 + 커넥션 5로 설정 → 커넥션 고갈 시뮬레이션
- 스레드 5 + 커넥션 20으로 설정 → 스레드 고갈 시뮬레이션
- 둘 중 뭐가 먼저 터지는지, 에러가 어떻게 다른지 비교

## 더 깊이 들어갈 주제
- G1GC의 Region 기반 수집 원리
- ZGC와 G1GC 비교 (pause time vs throughput)
- Netty의 PooledByteBufAllocator Arena/Chunk/Page 구조
- JIT의 C1/C2 컴파일러 차이
- Virtual Thread (Java 21)에서의 스레드풀 변화
