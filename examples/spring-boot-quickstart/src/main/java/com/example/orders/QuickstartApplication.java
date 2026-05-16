package com.example.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boots the Spring Boot application. The {@code outbox-spring} autoconfiguration
 * registers an {@link io.github.lobofoltran.outbox.Outbox} bean as soon as a
 * {@code DataSource} is in the context.
 */
@SpringBootApplication
public class QuickstartApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuickstartApplication.class, args);
    }
}
