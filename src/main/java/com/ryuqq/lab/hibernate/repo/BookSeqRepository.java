package com.ryuqq.lab.hibernate.repo;

import com.ryuqq.lab.hibernate.entity.BookSeq;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookSeqRepository extends JpaRepository<BookSeq, Long> {
}
