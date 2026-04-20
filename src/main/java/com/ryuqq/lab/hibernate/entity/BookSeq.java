package com.ryuqq.lab.hibernate.entity;

import jakarta.persistence.*;

/**
 * TABLE 전략으로 시퀀스 흉내 — 배치 인서트가 가능한 케이스.
 *
 * MySQL은 SEQUENCE를 지원하지 않음.
 * 대신 GenerationType.TABLE로 별도 테이블을 시퀀스처럼 사용.
 * 또는 allocationSize를 주면 여러 개 미리 받아와서 배치 가능.
 */
@Entity
@Table(name = "h_book_seq")
public class BookSeq {

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "book_seq_gen")
    @TableGenerator(
            name = "book_seq_gen",
            table = "h_id_sequences",
            pkColumnName = "seq_name",
            valueColumnName = "seq_value",
            pkColumnValue = "book_seq",
            allocationSize = 100  // 한번에 100개 받아옴 → 배치 가능
    )
    private Long id;

    @Column(nullable = false)
    private String title;

    protected BookSeq() {}

    public BookSeq(String title) {
        this.title = title;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
}
