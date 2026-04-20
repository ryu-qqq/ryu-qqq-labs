package com.ryuqq.lab.hibernate.repo;

import com.ryuqq.lab.hibernate.entity.CachedPost;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CachedPostRepository extends JpaRepository<CachedPost, Long> {
}
