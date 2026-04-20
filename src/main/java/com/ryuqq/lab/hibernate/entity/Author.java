package com.ryuqq.lab.hibernate.entity;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Author (1) ── (N) Book
 * 1차 캐시, N+1, fetch join, BatchSize 실험용.
 */
@Entity
@Table(name = "h_author")
public class Author {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @OneToMany(mappedBy = "author", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Book> books = new ArrayList<>();

    @Version
    private Long version;  // 낙관적 락

    protected Author() {}

    public Author(String name) {
        this.name = name;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Book> getBooks() { return books; }
    public Long getVersion() { return version; }

    public void addBook(Book book) {
        books.add(book);
        book.setAuthor(this);
    }
}
