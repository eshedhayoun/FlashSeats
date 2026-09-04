package com.flashseats.flashseats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point.
 *
 * <p>The bootstrap class lives in {@code com.flashseats.flashseats} while the nine modules live in
 * {@code com.flashseats.*}, so component scanning, entity scanning and repository scanning are all
 * widened explicitly. Without this the application would start and find none of the modules.
 *
 * <p>{@code @EnableScheduling} is load-bearing: the hold sweeper, the outbox relay, the queue
 * broadcaster and the promotion worker are all {@code @Scheduled}, and each is written to be safe
 * when it runs on every replica at once (global standards §7).
 */
@SpringBootApplication(scanBasePackages = "com.flashseats")
@EntityScan("com.flashseats")
@EnableJpaRepositories("com.flashseats")
@ConfigurationPropertiesScan("com.flashseats")
@EnableScheduling
public class FlashseatsApplication {

	public static void main(String[] args) {
		SpringApplication.run(FlashseatsApplication.class, args);
	}

}
