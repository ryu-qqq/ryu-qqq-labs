package com.ryuqq.lab.hibernate;

import com.ryuqq.lab.hibernate.entity.Author;
import com.ryuqq.lab.hibernate.entity.Book;
import com.ryuqq.lab.hibernate.entity.BookSeq;
import com.ryuqq.lab.hibernate.entity.CachedPost;
import com.ryuqq.lab.hibernate.repo.AuthorRepository;
import com.ryuqq.lab.hibernate.repo.BookRepository;
import com.ryuqq.lab.hibernate.repo.BookSeqRepository;
import com.ryuqq.lab.hibernate.repo.CachedPostRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Hibernate 내부 동작 실험.
 *
 * 모든 실험은 Hibernate Statistics를 리셋하고 시작 → 실행 후 쿼리 수 측정.
 * application-docker.yml의 hibernate.generate_statistics=true 필요.
 *
 * 콘솔에서 format_sql=true 설정된 SQL 로그도 같이 봐야 함:
 *   docker compose logs app --tail 50 | grep -A 3 "Hibernate:"
 */
@RestController
@RequestMapping("/hibernate")
@Profile("docker")
public class HibernateLabController {

    @PersistenceContext
    private EntityManager em;

    private final AuthorRepository authorRepo;
    private final BookRepository bookRepo;
    private final BookSeqRepository bookSeqRepo;
    private final CachedPostRepository cachedPostRepo;
    private final EntityManagerFactory emf;

    public HibernateLabController(AuthorRepository authorRepo, BookRepository bookRepo,
                                   BookSeqRepository bookSeqRepo, CachedPostRepository cachedPostRepo,
                                   EntityManagerFactory emf) {
        this.authorRepo = authorRepo;
        this.bookRepo = bookRepo;
        this.bookSeqRepo = bookSeqRepo;
        this.cachedPostRepo = cachedPostRepo;
        this.emf = emf;
    }

    // ── 공통: Statistics ──

    private Statistics stats() {
        return em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    private Map<String, Object> snapshot(String label, Statistics s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("queries", s.getQueryExecutionCount() + s.getPrepareStatementCount());
        m.put("prepares", s.getPrepareStatementCount());
        m.put("entityLoads", s.getEntityLoadCount());
        m.put("entityInserts", s.getEntityInsertCount());
        m.put("entityUpdates", s.getEntityUpdateCount());
        m.put("entityDeletes", s.getEntityDeleteCount());
        m.put("collectionLoads", s.getCollectionLoadCount());
        m.put("flushes", s.getFlushCount());
        m.put("secondLevelCacheHits", s.getSecondLevelCacheHitCount());
        return m;
    }

    // ── 실험용 테이블 초기화 / 데이터 세팅 ──

    @PostMapping("/setup")
    @Transactional
    public Map<String, Object> setup(@RequestParam(defaultValue = "5") int authors,
                                      @RequestParam(defaultValue = "3") int booksPerAuthor) {
        // 기존 데이터 싹 비우기 (cascade로 Book도 같이 삭제됨)
        authorRepo.deleteAllInBatch();
        em.createNativeQuery("DELETE FROM h_book").executeUpdate();
        em.createNativeQuery("DELETE FROM h_book_seq").executeUpdate();

        for (int a = 0; a < authors; a++) {
            Author author = new Author("Author-" + a);
            for (int b = 0; b < booksPerAuthor; b++) {
                author.addBook(new Book("Book-" + a + "-" + b));
            }
            authorRepo.save(author);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authors", authors);
        result.put("booksPerAuthor", booksPerAuthor);
        result.put("totalBooks", authors * booksPerAuthor);
        return result;
    }

    // ── 실험 1: PersistenceContext (1차 캐시) ──

    /**
     * 같은 트랜잭션 안에서 같은 ID 2번 조회 → 쿼리 1번만 나감.
     */
    @GetMapping("/first-cache")
    @Transactional
    public Map<String, Object> firstCache(@RequestParam Long authorId) {
        Statistics s = stats();
        s.clear();

        Author first = authorRepo.findById(authorId).orElseThrow();
        Map<String, Object> afterFirst = snapshot("1st findById", s);

        Author second = authorRepo.findById(authorId).orElseThrow();
        Map<String, Object> afterSecond = snapshot("2nd findById", s);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sameInstance", first == second);
        result.put("sameId", first.getId().equals(second.getId()));
        result.put("steps", List.of(afterFirst, afterSecond));
        result.put("설명", "같은 트랜잭션 + 같은 ID = 1차 캐시 히트. 쿼리 1번만 나감.");
        return result;
    }

    // ── 실험 2: Dirty Checking ──

    /**
     * save() 안 해도 필드 변경만으로 UPDATE가 나가는 현상.
     */
    @PostMapping("/dirty-checking")
    @Transactional
    public Map<String, Object> dirtyChecking(@RequestParam Long authorId,
                                              @RequestParam String newName) {
        Statistics s = stats();
        s.clear();

        Author author = authorRepo.findById(authorId).orElseThrow();
        Map<String, Object> afterLoad = snapshot("로드 직후", s);

        // 그냥 setter만 호출. save() 호출 안 함.
        author.setName(newName);
        Map<String, Object> afterSet = snapshot("setter 호출 직후 (flush 전)", s);

        em.flush();  // 강제로 flush
        Map<String, Object> afterFlush = snapshot("flush 직후", s);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("steps", List.of(afterLoad, afterSet, afterFlush));
        result.put("설명", "save() 호출 안 했는데 flush 시점에 UPDATE 나감. " +
                "Hibernate가 로드 시 스냅샷 저장 → flush 때 비교 → 변경된 필드만 UPDATE");
        return result;
    }

    // ── 실험 3: Flush 타이밍 ──

    /**
     * save() 직후 vs flush 직후 쿼리 수 비교.
     */
    @PostMapping("/flush-timing")
    @Transactional
    public Map<String, Object> flushTiming() {
        Statistics s = stats();
        s.clear();

        Author a = new Author("FlushTest");
        authorRepo.save(a);
        Map<String, Object> afterSave = snapshot("save() 직후", s);

        em.flush();
        Map<String, Object> afterFlush = snapshot("flush() 직후", s);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("steps", List.of(afterSave, afterFlush));
        result.put("note", "IDENTITY 전략이라 save() 시점에 ID 받기 위해 INSERT가 즉시 나감. " +
                "SEQUENCE/TABLE 전략이면 save() 직후엔 INSERT 없음, flush 시점에 몰아서 나감.");
        return result;
    }

    // ── 실험 4: 프록시 (findById vs getReference) ──

    /**
     * getReference는 쿼리 안 나감 → 프록시만 리턴.
     * 필드 접근 시점에 쿼리.
     */
    @GetMapping("/proxy")
    @Transactional
    public Map<String, Object> proxy(@RequestParam Long authorId) {
        Statistics s = stats();
        s.clear();

        Author ref = em.getReference(Author.class, authorId);
        Map<String, Object> afterGetRef = snapshot("getReference() 직후", s);

        String name = ref.getName();  // 실제 필드 접근
        Map<String, Object> afterAccess = snapshot("필드 접근 후", s);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("steps", List.of(afterGetRef, afterAccess));
        result.put("className", ref.getClass().getSimpleName());
        result.put("name", name);
        result.put("설명", "getReference()는 SQL 안 나감. 실제 필드 접근 시 쿼리. " +
                "className이 HibernateProxy$... 같은 형태면 프록시.");
        return result;
    }

    // ── 실험 5: N+1 ──

    /**
     * 기본 findAll → Author N개 + Book 조회 N번 (N+1)
     */
    @GetMapping("/n-plus-one")
    @Transactional
    public Map<String, Object> nPlusOne() {
        Statistics s = stats();
        s.clear();

        List<Author> authors = authorRepo.findAllAuthors();
        Map<String, Object> afterFind = snapshot("findAll 직후", s);

        int totalBooks = 0;
        for (Author a : authors) {
            totalBooks += a.getBooks().size();  // lazy 컬렉션 접근 → 매 author마다 쿼리
        }
        Map<String, Object> afterAccess = snapshot("books 접근 후", s);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authorCount", authors.size());
        result.put("totalBooks", totalBooks);
        result.put("steps", List.of(afterFind, afterAccess));
        result.put("설명", "author N명 + 각 author의 books 접근 N번 = 1 + N 쿼리 (N+1 문제)");
        return result;
    }

    @GetMapping("/fetch-join")
    @Transactional
    public Map<String, Object> fetchJoin() {
        Statistics s = stats();
        s.clear();

        List<Author> authors = authorRepo.findAllWithBooksFetchJoin();
        int totalBooks = authors.stream().mapToInt(a -> a.getBooks().size()).sum();
        Map<String, Object> after = snapshot("fetch join 완료", s);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authorCount", authors.size());
        result.put("totalBooks", totalBooks);
        result.put("steps", List.of(after));
        result.put("설명", "JOIN FETCH로 한 쿼리에 author + books 같이 로드. N+1 해결.");
        return result;
    }

    @GetMapping("/entity-graph")
    @Transactional
    public Map<String, Object> entityGraph() {
        Statistics s = stats();
        s.clear();

        List<Author> authors = authorRepo.findAllWithBooksEntityGraph();
        int totalBooks = authors.stream().mapToInt(a -> a.getBooks().size()).sum();
        Map<String, Object> after = snapshot("@EntityGraph 완료", s);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authorCount", authors.size());
        result.put("totalBooks", totalBooks);
        result.put("steps", List.of(after));
        result.put("설명", "@EntityGraph도 LEFT JOIN 유사한 방식으로 한 번에 로드.");
        return result;
    }

    // ── 실험 6: Cascade + orphanRemoval ──

    @PostMapping("/cascade-delete")
    @Transactional
    public Map<String, Object> cascadeDelete(@RequestParam Long authorId) {
        Statistics s = stats();
        s.clear();

        authorRepo.deleteById(authorId);
        em.flush();
        Map<String, Object> after = snapshot("deleteById 후 flush", s);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("steps", List.of(after));
        result.put("설명", "Author 삭제 → cascade로 Book도 같이 삭제됨. " +
                "entityDeletes에 Book + Author 둘 다 카운트.");
        return result;
    }

    // ── 실험 7: IDENTITY 배치 인서트 함정 ──

    /**
     * IDENTITY 전략으로 N개 save → 배치 무효화 확인.
     * Hibernate는 save 직후 generated ID를 알아야 해서 배치 못 묶음.
     */
    @PostMapping("/batch-identity")
    @Transactional
    public Map<String, Object> batchIdentity(@RequestParam(defaultValue = "100") int count) {
        Statistics s = stats();
        s.clear();

        long start = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            Book b = new Book("IdentityBook-" + i);
            bookRepo.save(b);
        }
        em.flush();
        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> after = snapshot("IDENTITY save N번", s);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", count);
        result.put("elapsedMs", elapsed);
        result.put("steps", List.of(after));
        result.put("설명", "IDENTITY 전략은 INSERT 실행해야 ID가 나와서 Hibernate가 배치 못 묶음. " +
                "prepares 수가 count와 거의 같음 = N번 네트워크 왕복. " +
                "batch_size 설정 무효화됨.");
        return result;
    }

    /**
     * TABLE 전략으로 같은 N개 → 배치 동작 확인.
     */
    @PostMapping("/batch-sequence")
    @Transactional
    public Map<String, Object> batchSequence(@RequestParam(defaultValue = "100") int count) {
        Statistics s = stats();
        s.clear();

        long start = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            BookSeq b = new BookSeq("SeqBook-" + i);
            bookSeqRepo.save(b);
        }
        em.flush();
        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> after = snapshot("TABLE(SEQUENCE 흉내) save N번", s);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", count);
        result.put("elapsedMs", elapsed);
        result.put("steps", List.of(after));
        result.put("설명", "TABLE/SEQUENCE 전략은 ID를 미리 받아둘 수 있음 → INSERT를 배치로 묶음. " +
                "prepares 수 << count (batch_size=50이면 count/50 언저리). " +
                "allocationSize=100으로 미리 받아와서 INSERT 중엔 ID 쿼리 안 나감.");
        return result;
    }

    // ── 실험 8: flush 모드 ──

    /**
     * AUTO (기본): JPQL 조회 전에 자동 flush → 쿼리 결과에 pending 변경 반영됨.
     * COMMIT: 커밋 직전에만 flush → 조회 시에도 이전 DB 상태 그대로 봄.
     */
    @PostMapping("/flush-mode")
    @Transactional
    public Map<String, Object> flushMode() {
        Statistics s = stats();
        s.clear();

        Author a = new Author("FlushModeTest");
        authorRepo.save(a);

        // AUTO 모드면 이 쿼리 전에 자동 flush됨 → count 포함됨
        Long countAutoBefore = (Long) em.createQuery(
                "SELECT COUNT(a) FROM Author a WHERE a.name = 'FlushModeTest'").getSingleResult();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("countAfterSaveBeforeCommit", countAutoBefore);
        result.put("설명", "AUTO 모드(기본)는 JPQL 실행 전 auto-flush. " +
                "save 직후 JPQL이 방금 저장한 것을 본다는 뜻. " +
                "COMMIT 모드면 커밋 전까지 DB에서 안 보임 (일관성 문제 주의).");
        result.put("stats", snapshot("종료", s));
        return result;
    }

    // ── 실험 9: 낙관적 락 (@Version) ──

    @PostMapping("/optimistic-lock")
    @Transactional
    public Map<String, Object> optimisticLock(@RequestParam Long authorId,
                                                @RequestParam String newName) {
        Author a = authorRepo.findById(authorId).orElseThrow();
        Long versionBefore = a.getVersion();
        a.setName(newName);
        em.flush();
        Long versionAfter = a.getVersion();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("versionBefore", versionBefore);
        result.put("versionAfter", versionAfter);
        result.put("설명", "@Version 필드가 자동으로 증가. " +
                "UPDATE 쿼리에 WHERE version = ? 조건 포함 → 동시 수정 감지. " +
                "버전 다르면 OptimisticLockException.");
        return result;
    }

    // ── 실험 10: 2차 캐시 ──

    /**
     * 2차 캐시 실험용 글 생성.
     */
    @PostMapping("/cache/setup")
    @Transactional
    public Map<String, Object> cacheSetup(@RequestParam(defaultValue = "5") int count) {
        em.createNativeQuery("DELETE FROM h_cached_post").executeUpdate();
        Statistics s = stats();
        s.clear();

        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CachedPost p = cachedPostRepo.save(
                    new CachedPost("Post-" + i, "content-" + i));
            ids.add(p.getId());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", count);
        result.put("ids", ids);
        return result;
    }

    /**
     * 2차 캐시 효과 측정.
     * 매번 새 EntityManager 만들어서 1차 캐시 효과를 배제하고 순수하게 2차 캐시만 측정.
     */
    @GetMapping("/cache/l2-test")
    public Map<String, Object> l2Test(@RequestParam Long postId) {
        Statistics s = stats();
        s.clear();

        // 각 라운드마다 EntityManager 새로 만들어서 1차 캐시 격리
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int round = 1; round <= 3; round++) {
            EntityManager newEm = emf.createEntityManager();
            try {
                newEm.getTransaction().begin();
                CachedPost post = newEm.find(CachedPost.class, postId);
                post.getTitle();  // 필드 접근
                newEm.getTransaction().commit();
            } finally {
                newEm.close();
            }
            steps.add(snapshot("라운드 " + round, s));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("steps", steps);

        Map<String, Object> cacheStats = new LinkedHashMap<>();
        cacheStats.put("hitCount", s.getSecondLevelCacheHitCount());
        cacheStats.put("missCount", s.getSecondLevelCacheMissCount());
        cacheStats.put("putCount", s.getSecondLevelCachePutCount());
        result.put("cacheStats", cacheStats);

        result.put("note", "매 라운드 새 EntityManager → 1차 캐시 효과 없음. " +
                "라운드 1: missCount +1, putCount +1 (DB 조회 + 2차 캐시 저장). " +
                "라운드 2~3: hitCount +1 씩 (2차 캐시에서 바로, DB 안 감).");
        return result;
    }

    // ── 실험 11: 여러 EntityManager (1차 캐시 분리) ──

    /**
     * 두 개의 EntityManager가 같은 엔티티를 조회하면 서로 다른 인스턴스 받음.
     * 1차 캐시가 EntityManager마다 독립적이라는 증명.
     */
    @GetMapping("/multi-em")
    public Map<String, Object> multiEm(@RequestParam Long authorId) {
        EntityManager em1 = emf.createEntityManager();
        EntityManager em2 = emf.createEntityManager();

        try {
            Author a1 = em1.find(Author.class, authorId);
            Author a2 = em2.find(Author.class, authorId);

            // 같은 EntityManager 안에서는 같은 인스턴스
            Author a1Again = em1.find(Author.class, authorId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("em1_hashCode", System.identityHashCode(a1));
            result.put("em2_hashCode", System.identityHashCode(a2));
            result.put("em1_again_hashCode", System.identityHashCode(a1Again));
            result.put("em1 == em2?", a1 == a2);
            result.put("em1 == em1Again?", a1 == a1Again);
            result.put("설명", "두 EntityManager의 1차 캐시는 서로 독립. " +
                    "같은 DB 행 조회해도 Java 객체는 서로 다름 (==). " +
                    "같은 EntityManager 안에선 같은 인스턴스.");
            return result;
        } finally {
            em1.close();
            em2.close();
        }
    }

    // ── 현재 상태 조회 ──

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        return snapshot("current", stats());
    }

    @GetMapping("/data")
    @Transactional(readOnly = true)
    public Map<String, Object> data() {
        List<Author> authors = authorRepo.findAllAuthors();
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Author a : authors) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("name", a.getName());
            row.put("version", a.getVersion());
            list.add(row);
        }
        result.put("authors", list);
        return result;
    }
}
