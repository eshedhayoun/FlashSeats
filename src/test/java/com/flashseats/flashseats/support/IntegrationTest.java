package com.flashseats.flashseats.support;

import com.flashseats.flashseats.FlashseatsApplication;
import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for tests that exercise real concurrency.
 *
 * <p>Real containers, not embedded fakes. Every correctness claim this system makes lives in
 * PostgreSQL row-lock semantics, conditional-update rowcounts and partial unique indexes — none of
 * which an in-memory database reproduces faithfully. A test against H2 would pass and prove nothing.
 *
 * <p>Containers are static, so one PostgreSQL and one Redis are shared by every test class in the
 * run rather than started per class.
 */
@SpringBootTest(
        classes = FlashseatsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IntegrationTest.Containers.class)
public abstract class IntegrationTest {

    /*
     * `classes` is named explicitly because it has to be. Spring's default is to search upward from
     * the test's own package for a @SpringBootApplication, and the bootstrap class lives in
     * com.flashseats.flashseats — so a test in com.flashseats.hold searches com.flashseats, finds
     * nothing, and fails with a message that does not mention packages at all.
     */

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withReuse(true);
        }

        @Bean
        @ServiceConnection
        RedisContainer redis() {
            return new RedisContainer(DockerImageName.parse("redis:7-alpine")).withReuse(true);
        }
    }
}
