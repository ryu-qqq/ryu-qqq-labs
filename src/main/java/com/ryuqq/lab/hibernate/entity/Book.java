package com.ryuqq.lab.hibernate.entity;

import jakarta.persistence.*;

/**
 * IDENTITY 전략 — MySQL auto_increment 사용.
 * 배치 인서트 시 Hibernate가 배치 못 묶는 대표적 케이스.
 */
@Entity
@Table(name = "h_book")
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private Author author;

    protected Book() {}

    public Book(String title) {
        this.title = title;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Author getAuthor() { return author; }
    public void setAuthor(Author author) { this.author = author; }
}
