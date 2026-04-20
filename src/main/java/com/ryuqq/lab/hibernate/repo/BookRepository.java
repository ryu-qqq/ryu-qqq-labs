package com.ryuqq.lab.hibernate.repo;

import com.ryuqq.lab.hibernate.entity.Book;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookRepository extends JpaRepository<Book, Long> {
}
