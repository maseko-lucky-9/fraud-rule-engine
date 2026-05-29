package com.capitec.fraud.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} so {@code OutboxPoller#drain} fires on its
 * configured cadence. Isolated to one annotation so we can disable in tests
 * via a {@code @TestConfiguration} override.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
