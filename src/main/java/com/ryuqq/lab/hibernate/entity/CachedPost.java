package com.ryuqq.lab.hibernate.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

/**
 * 2차 캐시 실험용 엔티티.
 *
 * @Cache로 Hibernate 2차 캐시 활성화.
 * READ_WRITE 전략: 동시성 있는 환경에서 일관성 보장.
 */
@Entity
@Table(name = "h_cached_post")
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class CachedPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 1000)
    private String content;

    protected CachedPost() {}

    public CachedPost(String title, String content) {
        this.title = title;
        this.content = content;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public void setTitle(String title) { this.title = title; }
    public void setContent(String content) { this.content = content; }
}
