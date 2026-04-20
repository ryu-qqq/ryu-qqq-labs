# Hibernate 내부 동작 실험 기록

> 브랜치: `lab/hibernate-internals`

## 핵심 개념

### EntityManager의 역할
```
엔티티 상태 추적자 (커넥션 관리자 아님)

하는 일:
  1. 엔티티 상태 (Transient/Managed/Detached/Removed) 관리
  2. 1차 캐시 (PersistenceContext)
  3. Dirty Checking (스냅샷 비교)
  4. Write-Behind (쓰기 지연, Action Queue)
  5. 커넥션 빌려 쓰기 (소유 X, HikariCP에서 대여)

생명주기:
  - 기본: 트랜잭션마다 new / close
  - OSIV=true면 HTTP 요청 단위
```

### OSIV (Open Session In View)
```
spring.jpa.open-in-view=true (Spring Boot 기본)
  → EntityManager가 HTTP 요청 전체에서 살아있음
  → Controller/View에서 lazy 로딩 가능
  
문제:
  - 커넥션 점유 시간이 Service 시간이 아니라 HTTP 응답 전체 시간
  - 풀 고갈 훨씬 쉽게 발생
  → 프로덕션: false 권장
```

## 실험 1: PersistenceContext (1차 캐시)

```bash
curl -s 'localhost:8080/hibernate/first-cache?authorId=1' | python3 -m json.tool
```

```
1차 findById:  queries=1, entityLoads=1
2차 findById:  queries=1, entityLoads=1  ← 변화 없음!
sameInstance: true

→ EntityManager 내부 Map<ID, Entity>에서 재사용
→ 트랜잭션 끝나면 사라짐 (GC)
```

## 실험 2: Dirty Checking

```bash
curl -s -X POST 'localhost:8080/hibernate/dirty-checking?authorId=1&newName=NewName'
```

```
로드 직후:   entityUpdates=0 (스냅샷 저장)
setter 후:   entityUpdates=0 (메모리만 바뀜)
flush 직후:  entityUpdates=1 (스냅샷 비교 후 UPDATE 생성)

save() 안 호출해도 필드 변경만으로 UPDATE 나감.
```

### 실무 함정
```
대량 조회 시 스냅샷 N개 저장 → 메모리 낭비
조회만 할 거면:
  @Transactional(readOnly = true)  ← 스냅샷 생성 생략
  @QueryHint(name = "org.hibernate.readOnly", value = "true")
```

## 실험 3: Flush 타이밍 + IDENTITY 함정

### 일반적인 save() 동작
```
save() → Action Queue에 INSERT 예약 (flush 전까지 쌓아둠)
flush() → 쌓인 것들 몰아서 실행 → 배치 가능
```

### IDENTITY 예외
```bash
curl -s -X POST 'localhost:8080/hibernate/flush-timing'
```

```
save() 직후: entityInserts=1, flushes=0
  → 이미 INSERT 나감! (쓰기 지연 안 됨)

이유: auto_increment는 DB 실행해야 ID가 나옴
    → Hibernate가 ID 받으려고 즉시 INSERT
    → 배치 불가능
```

### 배치 비교 실험
```bash
curl -s -X POST 'localhost:8080/hibernate/batch-identity?count=100'
curl -s -X POST 'localhost:8080/hibernate/batch-sequence?count=100'
```

```
                IDENTITY    TABLE (SEQUENCE)
elapsedMs       67ms        38ms              ← 1.8배
prepares        100번       1번               ← 100배 차이
```

### 해결책
```
Case 1: MySQL + auto_increment 유지
  → JPA 대신 JdbcTemplate.batchUpdate (450배 빠름)

Case 2: JPA로 대량 insert
  → GenerationType.SEQUENCE (PostgreSQL)
  → GenerationType.TABLE + allocationSize (MySQL에서)
  → UUID
```

## 실험 4: 프록시 (getReference vs findById)

```bash
curl -s 'localhost:8080/hibernate/proxy?authorId=2'
```

```
getReference() 직후: queries=0, entityLoads=0
필드 접근 후:        queries=1, entityLoads=1

className: Author$HibernateProxy ← 진짜 Author 아닌 프록시
```

### 유용한 패턴
```java
// ❌ 비효율: Author 전체 조회
Author a = authorRepo.findById(1L).get();
book.setAuthor(a);

// ✅ getReference: 쿼리 안 나감
Author a = em.getReference(Author.class, 1L);
book.setAuthor(a);  // FK만 필요하니 Author 로드 불필요
```

### 함정: LazyInitializationException
```
트랜잭션 밖에서 프록시 필드 접근 → 쿼리 날리려는데 EntityManager 닫힘
→ 이게 OSIV=true가 존재하는 이유
→ 근데 프로덕션에선 Service 안에서 필요한 데이터 다 로딩하는 게 맞음
```

## 실험 5: N+1

```bash
curl -s 'localhost:8080/hibernate/n-plus-one'
curl -s 'localhost:8080/hibernate/fetch-join'
curl -s 'localhost:8080/hibernate/entity-graph'
```

```
                    queries   collectionLoads
N+1 (나쁨)           8         6
fetch join (해결)    2         5
@EntityGraph (해결)  2         6
```

### fetch join vs @EntityGraph
```
fetch join (@Query):
  ✅ 명시적, 어떤 조인인지 명확
  ❌ 메서드마다 따로 작성, Pageable과 조합 시 주의

@EntityGraph:
  ✅ 선언적, 페이징과 조합 가능
  ❌ 복잡한 조인은 표현 어려움

대안: @BatchSize(size=10) → IN 쿼리로 묶어서 1 + N/10
```

## 실험 6: Cascade + orphanRemoval

```bash
curl -s -X POST 'localhost:8080/hibernate/cascade-delete?authorId=2'
```

```
Author 1개 삭제 → Book 3개도 자동 삭제
entityDeletes: 4
```

### REMOVE vs orphanRemoval
```
CascadeType.REMOVE:
  delete(parent) → children 삭제 O
  parent.children.clear() → 안 지워짐

orphanRemoval=true:
  delete(parent) → children 삭제 O
  parent.children.clear() → 지워짐 ("고아"가 되면 삭제)
```

## 실험 8: Flush 모드

```
AUTO (기본):
  JPQL/Native 쿼리 실행 전 auto-flush
  → save() 직후 COUNT 쿼리에 방금 저장한 것 포함됨

COMMIT:
  commit 직전에만 flush
  → save 후 조회해도 DB에 없음 (일관성 문제)

권장: AUTO 유지
```

## 실험 9: 낙관적 락 (@Version)

```bash
curl -s -X POST 'localhost:8080/hibernate/optimistic-lock?authorId=3&newName=Locked'
```

```
version: 0 → 1 자동 증가

UPDATE 쿼리:
  UPDATE author SET name=?, version=1 WHERE id=? AND version=0
  → 다른 트랜잭션이 먼저 수정했으면 0행 영향 → OptimisticLockException
```

### 낙관적 vs 비관적
```
낙관적 (@Version):
  DB 락 없음 → 성능 좋음
  충돌 시 재시도 필요
  읽기 많고 쓰기 충돌 적을 때

비관적 (SELECT FOR UPDATE):
  DB X-lock → 확실한 제어
  다른 트랜잭션 대기 → 병목
  재고 차감 등 확실히 성공시켜야 할 때
```

## 실험 10: 2차 캐시 (L2 Cache)

### 설정
```yaml
spring.jpa.properties.hibernate:
  cache:
    use_second_level_cache: true
    region.factory_class: jcache
  javax.cache.provider: org.ehcache.jsr107.EhcacheCachingProvider
```

```java
@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class CachedPost { ... }
```

### 실험
```bash
curl -s -X POST 'localhost:8080/hibernate/cache/setup?count=3'
curl -s 'localhost:8080/hibernate/cache/l2-test?postId=1'
```

```
라운드 1: miss +1, put +1 (DB 조회 + 캐시 저장)
라운드 2: hit +1 (DB 안 감)
라운드 3: hit +1 (DB 안 감)

→ 1차 캐시와 달리 트랜잭션 경계 넘어 공유
```

### 실무에서 잘 안 쓰는 이유
```
1. 분산 환경에서 서버 간 캐시 불일치
2. 네이티브 쿼리로 수정 시 캐시 무효화 안 됨
3. 대안: Redis + Spring @Cacheable (더 유연)
```

## 실험 11: 여러 EntityManager (1차 캐시 분리)

```bash
curl -s 'localhost:8080/hibernate/multi-em?authorId=1'
```

```
em1 == em2?       false  ← 다른 인스턴스 (1차 캐시 별개)
em1 == em1Again?  true   ← 같은 EM 안에선 동일 인스턴스
```

### 실무 의미
```
JPA 엔티티는 equals/hashCode 재정의 필수:
  다른 트랜잭션에서 조회한 같은 데이터 == 비교는 false
  → ID 기반 equals 재정의 필요
```

## 주요 설정 요약 (application-docker.yml)

```yaml
spring:
  jpa:
    open-in-view: false  # ★ 프로덕션 필수
    properties:
      hibernate:
        jdbc:
          batch_size: 50
        order_inserts: true
        order_updates: true
        batch_versioned_data: true
        generate_statistics: true
        cache:
          use_second_level_cache: true
          region.factory_class: jcache
```

## API 엔드포인트

| 엔드포인트 | 설명 |
|-----------|------|
| `POST /hibernate/setup?authors=5&booksPerAuthor=3` | 데이터 초기화 |
| `GET /hibernate/data` | 전체 Author 조회 |
| `GET /hibernate/first-cache?authorId=1` | 1차 캐시 |
| `POST /hibernate/dirty-checking?authorId=1&newName=X` | Dirty Checking |
| `POST /hibernate/flush-timing` | Flush 타이밍 |
| `GET /hibernate/proxy?authorId=1` | 프록시 |
| `GET /hibernate/n-plus-one` | N+1 재현 |
| `GET /hibernate/fetch-join` | fetch join 해결 |
| `GET /hibernate/entity-graph` | EntityGraph 해결 |
| `POST /hibernate/cascade-delete?authorId=1` | Cascade |
| `POST /hibernate/batch-identity?count=100` | IDENTITY 함정 |
| `POST /hibernate/batch-sequence?count=100` | TABLE 전략 |
| `POST /hibernate/flush-mode` | Flush 모드 |
| `POST /hibernate/optimistic-lock?authorId=1&newName=X` | 낙관적 락 |
| `POST /hibernate/cache/setup?count=3` | 2차 캐시 데이터 |
| `GET /hibernate/cache/l2-test?postId=1` | 2차 캐시 테스트 |
| `GET /hibernate/multi-em?authorId=1` | 여러 EM |
