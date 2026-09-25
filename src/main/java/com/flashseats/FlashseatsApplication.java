package com.flashseats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point. It sits at the root package, so component, entity and repository
 * scanning cover every module without widening, and each direct sub-package is a Modulith module.
 *
 * <p>{@code @EnableScheduling} is load-bearing: the sweepers, the outbox relay, the broadcaster and
 * the promotion worker are {@code @Scheduled}, each safe to run on every replica at once
 * (global standards §7).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FlashseatsApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashseatsApplication.class, args);
    }
}
