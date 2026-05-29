package com.capitec.fraud;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		// Pinned to a known-stable native image; the `:latest` tag was rotating
		// and slow to come up on GitHub-hosted runners ("Timed out waiting for
		// log output matching '.*Transitioning from RECOVERY to RUNNING.*'").
		// 3 min startup timeout absorbs the runner cold-start variance.
		return new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"))
				.withStartupTimeout(Duration.ofMinutes(3));
	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
				.withExposedPorts(6379);
	}

}
