-- 학습용 테이블
CREATE TABLE IF NOT EXISTS lab_test (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 테스트 데이터
INSERT INTO lab_test (name) VALUES ('init-data-1'), ('init-data-2'), ('init-data-3');
