package com.spe.smartdocjp.integration;

import com.spe.smartdocjp.support.DeterministicAiTestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Optional reuse of the same scenario against MySQL 8. Not part of the default test run.
 */
@Import(DeterministicAiTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class MySqlContainerVerticalSliceIT extends AbstractVerticalSliceIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("smartdoc_test")
            .withUsername("smartdoc")
            .withPassword("smartdoc");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }
}
