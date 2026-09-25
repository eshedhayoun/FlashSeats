package com.flashseats.app.support;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IntegrationTest.Containers.class)
public abstract class IntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withReuse(true);
        }

        /**
         * {@code notify-keyspace-events Ex} is not a tuning flag here; without it the hold expiry
         * listener subscribes successfully and receives nothing, so every test would pass while the
         * fast path silently did not exist. The stock image ships the setting empty, so it has to be
         * asked for — {@code docker/redis/redis.conf} does the same for the real stack.
         *
         * <p>{@code E} is the key-EVENT channel, whose message is the key name. {@code K} publishes
         * the event name to a per-key channel instead and the listener never fires (ADR-003).
         */
        @Bean
        @ServiceConnection
        RedisContainer redis() {
            return new RedisContainer(DockerImageName.parse("redis:7-alpine"))
                    .withCommand("redis-server", "--notify-keyspace-events", "Ex")
                    .withReuse(true);
        }
    }
}
