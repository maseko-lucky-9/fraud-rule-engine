package com.capitec.fraud.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Exposes a {@link Clock} bean so time-sensitive predicates (e.g. timeOfDay) are
 * testable via {@code Clock.fixed(...)}. Default is system UTC.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
