package org.qubership.integration.platform.engine.camel.components.kafka.consumer;

import com.netcracker.cloud.maas.bluegreen.kafka.CommitMarker;
import com.netcracker.cloud.maas.bluegreen.kafka.Record;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.component.kafka.consumer.support.KafkaRecordProcessor;
import org.apache.camel.component.kafka.serde.KafkaHeaderDeserializer;
import org.apache.camel.spi.ExceptionHandler;
import org.apache.camel.spi.HeaderFilterStrategy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.qubership.integration.platform.engine.camel.components.kafka.cloudcore.BGKafkaConsumerExtended;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;

import java.time.Duration;
import java.util.stream.StreamSupport;

/**
 * Based on {@link KafkaRecordProcessor}
 */
@Slf4j
public class KafkaBGRecordProcessor {

    private final KafkaCustomConfiguration configuration;
    private final Processor processor;
    private final BGKafkaConsumerExtended<?, ?> consumer;

    public static final class ProcessResult {

        private static final ProcessResult UNPROCESSED_RESULT =
            new ProcessResult(false, null);

        private final boolean breakOnErrorHit;
        private final CommitMarker partitionLastOffset;

        private ProcessResult(boolean breakOnErrorHit, CommitMarker partitionLastOffset) {
            this.breakOnErrorHit = breakOnErrorHit;
            this.partitionLastOffset = partitionLastOffset;
        }

        public boolean isBreakOnErrorHit() {
            return breakOnErrorHit;
        }

        public CommitMarker getPartitionLastOffset() {
            return partitionLastOffset;
        }

        public static ProcessResult newUnprocessed() {
            return UNPROCESSED_RESULT;
        }
    }

    public KafkaBGRecordProcessor(KafkaCustomConfiguration configuration, Processor processor,
        BGKafkaConsumerExtended<?, ?> consumer) {
        this.configuration = configuration;
        this.processor = processor;
        this.consumer = consumer;
    }

    private void setupExchangeMessage(Message message, Record<?, ?> record) {
        ConsumerRecord<?, ?> consumerRecord = record.getConsumerRecord();

        message.setHeader(KafkaConstants.PARTITION, consumerRecord.partition());
        message.setHeader(KafkaConstants.TOPIC, consumerRecord.topic());
        message.setHeader(KafkaConstants.OFFSET, consumerRecord.offset());
        message.setHeader(KafkaConstants.HEADERS, consumerRecord.headers());
        message.setHeader(KafkaConstants.TIMESTAMP, consumerRecord.timestamp());
        message.setHeader(Exchange.MESSAGE_TIMESTAMP, consumerRecord.timestamp());

        if (consumerRecord.key() != null) {
            message.setHeader(KafkaConstants.KEY, consumerRecord.key());
        }

        message.setBody(consumerRecord.value());
    }

    private boolean shouldBeFiltered(Header header, Exchange exchange,
        HeaderFilterStrategy headerFilterStrategy) {
        return !headerFilterStrategy.applyFilterToExternalHeaders(header.key(), header.value(),
            exchange);
    }

    private void propagateHeaders(Record<Object, Object> record, Exchange exchange) {

        HeaderFilterStrategy headerFilterStrategy = configuration.getHeaderFilterStrategy();
        KafkaHeaderDeserializer headerDeserializer = configuration.getHeaderDeserializer();

        StreamSupport.stream(record.getConsumerRecord().headers().spliterator(), false)
            .filter(header -> shouldBeFiltered(header, exchange, headerFilterStrategy))
            .forEach(header -> exchange.getIn().setHeader(header.key(),
                headerDeserializer.deserialize(header.key(), header.value())));
    }

    public ProcessResult processExchange(
        Exchange exchange, Record<Object, Object> record, ProcessResult lastResult,
        ExceptionHandler exceptionHandler) {

        Message message = exchange.getMessage();

        setupExchangeMessage(message, record);
        propagateHeaders(record, exchange);

        try {
            processor.process(exchange);
        } catch (Exception e) {
            exchange.setException(e);
            boolean breakOnErrorExit = processException(exchange,
                lastResult.getPartitionLastOffset(), exceptionHandler);
            return new ProcessResult(breakOnErrorExit, lastResult.getPartitionLastOffset());
        }

        return new ProcessResult(false, record.getCommitMarker());
    }

    private boolean processException(Exchange exchange, CommitMarker partitionLastOffset,
        ExceptionHandler exceptionHandler) {

        // processing failed due to an unhandled exception, what should we do
        if (configuration.isBreakOnFirstError()) {
            // we are failing and we should break out
            if (log.isWarnEnabled()) {
                log.warn("Error during processing {}", exchange, exchange.getException());
                log.warn("Will seek consumer to offset {} and start polling again.",
                    partitionLastOffset);
            }

            // force commit, so we resume on next poll where we failed
            commitOffset(partitionLastOffset, false, true);

            // continue to next partition
            return true;
        } else {
            // will handle/log the exception and then continue to next
            exceptionHandler.handleException("Error during processing", exchange,
                exchange.getException());
        }

        return false;
    }

    public void commitOffset(CommitMarker partitionLastOffset, boolean stopping,
        boolean forceCommit) {
        commitOffset(configuration, consumer, partitionLastOffset, stopping, forceCommit);
    }

    public static void commitOffset(
        KafkaCustomConfiguration configuration, BGKafkaConsumerExtended<?, ?> consumer,
        CommitMarker commitMarker,
        boolean stopping, boolean forceCommit) {

        if (commitMarker == null) {
            return;
        }

        // offset repo from original class has been removed
        if (stopping) {
            commitSync(configuration, consumer, commitMarker);
        } else if (forceCommit) {
            forceSyncCommit(configuration, consumer, commitMarker);
        }
    }

    private static void commitOffset(
        KafkaCustomConfiguration configuration, BGKafkaConsumerExtended<?, ?> consumer,
        CommitMarker commitMarker) {
        long timeout = configuration.getCommitTimeoutMs();
        consumer.commitSync(commitMarker,
            Duration.ofMillis(timeout)); // CommitMarker = last_record_offset + 1
    }

    private static void forceSyncCommit(
        KafkaCustomConfiguration configuration, BGKafkaConsumerExtended<?, ?> consumer,
        CommitMarker commitMarker) {
        commitOffset(configuration, consumer, commitMarker);
    }

    private static void noCommit() {
        // do nothing
    }

    private static void commitSync(
        KafkaCustomConfiguration configuration, BGKafkaConsumerExtended<?, ?> consumer,
        CommitMarker partitionLastOffset) {
        commitOffset(configuration, consumer, partitionLastOffset);
    }


}
