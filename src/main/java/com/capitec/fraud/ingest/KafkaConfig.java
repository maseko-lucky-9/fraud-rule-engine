package com.capitec.fraud.ingest;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka-side wiring. We declare all four topics with explicit partition/replica
 * counts so the cluster doesn't auto-create them with surprising defaults.
 *
 * <p>The error handler routes non-recoverable failures to {@link KafkaTopics#EVENTS_DLT}
 * with a fixed back-off of 3 attempts × 500ms — beyond which the original
 * event is parked in the DLT for human triage.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Bean
    public NewTopic eventsInTopic() {
        return TopicBuilder.name(KafkaTopics.EVENTS_IN).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic decisionsOutTopic() {
        return TopicBuilder.name(KafkaTopics.DECISIONS_OUT).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic eventsRetryTopic() {
        return TopicBuilder.name(KafkaTopics.EVENTS_RETRY).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic eventsDltTopic() {
        return TopicBuilder.name(KafkaTopics.EVENTS_DLT).partitions(3).replicas(1).build();
    }

    @Bean
    public DefaultErrorHandler errorHandler(KafkaOperations<Object, Object> template) {
        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(
                template,
                (rec, ex) -> new org.apache.kafka.common.TopicPartition(KafkaTopics.EVENTS_DLT, rec.partition())
        );
        return new DefaultErrorHandler(dlt, new FixedBackOff(500L, 3));
    }
}
