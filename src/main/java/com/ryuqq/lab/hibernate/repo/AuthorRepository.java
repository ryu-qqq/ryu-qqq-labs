package com.ryuqq.lab.hibernate.repo;

import com.ryuqq.lab.hibernate.entity.Author;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.List;

public interface AuthorRepository extends JpaRepository<Author, Long> {

    /** 일반 조회 — N+1 유발 대상 */
    @Query("SELECT a FROM Author a")
    List<Author> findAllAuthors();

    /** fetch join — N+1 해결 */
    @Query("SELECT DISTINCT a FROM Author a JOIN FETCH a.books")
    List<Author> findAllWithBooksFetchJoin();

    /** @EntityGraph — N+1 해결 */
    @EntityGraph(attributePaths = {"books"})
    @Query("SELECT a FROM Author a")
    List<Author> findAllWithBooksEntityGraph();

    /** 읽기 전용 힌트 — dirty checking 생략 */
    @QueryHints(@QueryHint(name = "org.hibernate.readOnly", value = "true"))
    @Query("SELECT a FROM Author a WHERE a.id = :id")
    Author findReadOnlyById(Long id);
}
