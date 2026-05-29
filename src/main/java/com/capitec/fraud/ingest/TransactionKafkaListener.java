package com.capitec.fraud.ingest;

import com.capitec.fraud.domain.Transaction;
import com.capitec.fraud.observability.PiiRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Async ingest. Each message is processed exactly once (per
 * {@link IngestService}'s consumer-side dedup table); a poison message that
 * fails 3 retries lands in {@link KafkaTopics#EVENTS_DLT} by
 * {@link KafkaConfig#errorHandler}.
 */
@Component
public class TransactionKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionKafkaListener.class);

    private final IngestService ingestService;

    public TransactionKafkaListener(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @KafkaListener(
            topics = KafkaTopics.EVENTS_IN,
            groupId = "${spring.kafka.consumer.group-id:fraud-engine}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onEvent(TransactionEvent event, Acknowledgment ack) {
        try {
            Transaction tx = toDomain(event);
            var decision = ingestService.ingest(tx, IngestService.CONSUMER_KAFKA);
            log.debug("ingested tx={} account={} → {} score={}",
                    tx.txId(), PiiRedactor.redactAccountId(tx.accountId()),
                    decision.status(), decision.score());
            ack.acknowledge();
        } catch (RuntimeException e) {
            // Bubble up so DefaultErrorHandler routes through retry / DLT.
            log.warn("ingest failed for tx={} account={} — will be retried then DLT'd. cause={}",
                    event.txId(), PiiRedactor.redactAccountId(event.accountId()), e.toString());
            throw e;
        }
    }

    private static Transaction toDomain(TransactionEvent e) {
        if (e.txId() == null || e.accountId() == null || e.amount() == null) {
            throw new IllegalArgumentException("Required fields missing on TransactionEvent");
        }
        return new Transaction(
                e.txId(),
                e.accountId(),
                e.amount(),
                e.currency(),
                e.mcc(),
                Transaction.Channel.valueOf(e.channel()),
                e.country(),
                e.ipCountry(),
                e.deviceId(),
                e.merchantId(),
                e.accountAgeDays() == null ? 0 : e.accountAgeDays(),
                e.timestamp()
        );
    }
}
