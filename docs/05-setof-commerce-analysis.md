# 05. setof-commerce 성능 분석 기록

## 배경

setof-commerce Prod 서버(ECS Fargate)가 느리다는 보고.
www.set-of.com 사이트 전반적으로 응답이 느림.

## 측정 결과

### 백엔드 API (순수 서버 응답시간)

| 엔드포인트 | TTFB |
|-----------|------|
| GNB | 40~179ms |
| 콘텐츠 상세 | 72ms |
| 상품 상세 | 90~187ms |
| 상품 목록 | 187~252ms |
| 카테고리 | 44~86ms |

→ API 자체는 빠름 (60~250ms)

### SSR (Next.js 서버 렌더링 포함)

| 페이지 | TTFB |
|--------|------|
| 메인 | 264~1,097ms |
| 랭킹 | 349~1,050ms |
| 여성 | 180~1,249ms |
| 상품상세 | 145~305ms |

→ SSR이 300ms~1.2초로 들쭉날쭉. 병목은 Next.js SSR 서버.

### 결론
API 서버는 빠르다. 느린 원인은:
1. Next.js SSR 서버의 렌더링 시간
2. 외부 이미지(sabangnet) 256~312KB JPEG + cache-control: no-cache

## 백엔드 코드 병목 (잠재적)

### N+1 Wishlist 쿼리 (Critical)
- 상품 목록 조회 시 로그인 회원이면 각 상품마다 findByMemberIdAndProductGroupId() 개별 호출
- 20개 상품 = 20개 추가 쿼리
- 해결: Batch IN 쿼리로 변경

### HikariCP 커넥션 풀 (prod: 20개)
- Tomcat 스레드 200개 vs HikariCP 20개 → 동시 요청 시 커넥션 대기
- N+1 먼저 잡고 풀은 소폭(30~40) 올리는 게 안전
- 무작정 키우면 DB 서버에 부하 전가 (MySQL max_connections 주의)

### 캐시 현황
| 엔드포인트 | 캐시 | TTL |
|-----------|------|-----|
| GNB | Caffeine(로컬) | 5분 |
| 콘텐츠 상세 | Redis | 10분 |
| 카테고리 | Caffeine | 5분 |
| 브랜드 | Caffeine | 10분 |
| 배너 | Caffeine | 3분 |
| **상품 목록** | **없음** | - |
| **상품 상세** | Redis (있음) | 5분 |

→ 가장 호출 빈도 높은 상품 목록에 캐시 없음

## ECS 인프라 변경

### 변경 사항
1. ECS Task Memory: 1024MB → **2048MB**
2. Dockerfile MaxRAMPercentage: 75% → **50%**
3. Dockerfile InitialRAMPercentage: 50% → **30%**

### 변경 이유
1024MB 컨테이너에 MaxRAM 75% = 힙 768MB + Non-Heap ~300MB = 컨테이너 한도에 바짝 붙음.
2048MB로 올리고 50%로 잡아서 힙 1024MB + 여유 594MB 확보.

## 이미지 성능 이슈

| 소스 | 포맷 | 파일 크기 | CDN 캐시 |
|------|------|----------|----------|
| cdn.set-of.com (자체) | WebP | 52KB | CloudFront |
| sabangnet (외부 셀러) | JPEG | 256~312KB | no-cache |

외부 이미지가 5배 무겁고 캐시도 없음. 해당 상품은 비활성화 처리함.

## 레거시 모듈 정리

같은 세션에서 진행한 작업:
- bootstrap-legacy-web-api 삭제 (ECS desired_count=0 확인 후)
- bootstrap-batch 삭제 (ECS 없음)
- settings.gradle, build.gradle, deploy.yml, docker-compose.aws.yml, terraform 정리
