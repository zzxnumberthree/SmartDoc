package com.spe.smartdocjp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA configuration class enabling JPA auditing (createdAt, updatedAt automatic population).
 * Separated from the main application class to allow sliced web tests (@WebMvcTest) to run cleanly.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
