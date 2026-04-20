package com.ryuqq.lab.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/**
 * Bulk Insert 실험용: rewriteBatchedStatements=true로 설정된 DataSource.
 *
 * 기본 DataSource(LabPool)는 그대로 두고, 실험용으로 하나 추가 생성.
 * 기본 DataSource: rewriteBatchedStatements 없음
 * bulkInsertOn:   rewriteBatchedStatements=true
 */
@Configuration
@Profile("docker")
public class BulkInsertConfig {

    @Bean("bulkInsertOn")
    public DataSource bulkInsertOn() {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("BulkOnPool");
        cfg.setJdbcUrl("jdbc:mysql://mysql:3306/labdb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&rewriteBatchedStatements=true");
        cfg.setUsername("lab");
        cfg.setPassword("lab1234");
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(5000);
        return new HikariDataSource(cfg);
    }
}
