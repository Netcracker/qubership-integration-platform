package org.qubership.integration.platform.engine.camel.components.kafka.consumer;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.component.kafka.KafkaFetchRecords;
import org.apache.camel.component.kafka.PollOnError;
import org.apache.camel.support.BridgeExceptionHandlerToErrorHandler;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import com.netcracker.cloud.maas.bluegreen.kafka.CommitMarker;
import com.netcracker.cloud.maas.bluegreen.kafka.ConsumerConsistencyMode;
import com.netcracker.cloud.maas.bluegreen.kafka.Record;
import com.netcracker.cloud.maas.bluegreen.kafka.RecordsBatch;
import org.qubership.integration.platform.engine.camel.components.kafka.cloudcore.BGKafkaConsumerExtended;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Based on {@link KafkaFetchRecords} version 3.14. Replaced consumer class to support blue-green,
 * fixed and improved retry logic in {@link KafkaBGFetchRecords#run}. Following methods
 * {@link KafkaBGFetchRecords#startPolling}, {@link KafkaBGFetchRecords#seekToNextOffset},
 * {@link KafkaBGFetchRecords#processPolledRecords},
 * {@link KafkaBGFetchRecords#handleAccordingToStrategy} has many changes for support blue-green
 * mode.
 */
@Slf4j
public class KafkaBGFetchRecords implements Runnable {

    public static final int RECONNECT_DELAY = 2500;
    private final KafkaBGConsumer kafkaConsumer;
    private BGKafkaConsumerExtended consumer;
    private final String topicName;
    private final Pattern topicPattern;
    private final String threadId;
    private final Properties kafkaProps;
    private final ConsumerConsistencyMode consistencyMode;
    private final PollOnError pollOnError;
    private final BridgeExceptionHandlerToErrorHandler bridge;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private boolean retry = true;
    private boolean reconnect; // must be false at init (this is the policy whether to reconnect)
    private boolean connected; // this is the state (connected or not)
    private boolean consumerClosed = true;

    KafkaBGFetchRecords(KafkaBGConsumer kafkaConsumer,
        BridgeExceptionHandlerToErrorHandler bridge, String topicName, Pattern topicPattern,
        String id,
        Properties kafkaProps, ConsumerConsistencyMode consistencyMode, PollOnError pollOnError) {
        this.kafkaConsumer = kafkaConsumer;
        this.bridge = bridge;
        this.topicName = topicName;
        this.topicPattern = topicPattern;
        this.pollOnError = pollOnError;
        this.threadId = topicName + "-" + "Thread " + id;
        this.kafkaProps = kafkaProps;
        this.consistencyMode = consistencyMode;
    }

    @Override
    public void run() {
        if (!isKafkaConsumerRunnable()) {
            return;
        }

        try {
            do {
                try {
                    if (consumerClosed) {
                        createConsumer();

                        setReconnect(false); // fix for original component
                        setConnected(true);
                        setRetry(true);

                        consumerClosed = false;
                    }
                } catch (Exception e) {
                    setConnected(false);
                    log.warn(
                        "Error creating org.apache.kafka.clients.consumer.KafkaConsumer due {}",
                        e.getMessage(), e);
                    continue;
                }

                // lock thread inside polling (normal operation)
                // if error throws - exit from method end re-create consumer in loop
                startPolling();

                // delay for reconnect
                if (continuePolling() && consumerClosed) {
                    log.debug("Reconnect kafka consumer for topic: {}", getPrintableTopic());
                    try {
                        Thread.sleep(RECONNECT_DELAY);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            } while (continuePolling());

            if (log.isInfoEnabled()) {
                log.info("Terminating KafkaConsumer thread: {} receiving from {}", threadId,
                    getPrintableTopic());
            }
        } finally {
            closeConsumer();
        }
    }

    private boolean continuePolling() {
        return (isRetrying() || isReconnect()) && isKafkaConsumerRunnable();
    }

    protected void createConsumer() {
        // this may throw an exception if something is wrong with kafka consumer
        this.consumer = kafkaConsumer.getEndpoint().getKafkaClientFactory()
            .getConsumer(kafkaProps, consistencyMode, Arrays.asList(topicName.split(",")));
    }

    protected void startPolling() {
        Pair<KafkaBGRecordProcessor.ProcessResult, Record<Object, Object>> lastResult = null;
        KafkaBGRecordProcessor kafkaRecordProcessor = buildKafkaRecordProcessor();
        try {
            /*
             * We lock the processing of the record to avoid raising a WakeUpException as a result to a call
             * to stop() or shutdown().
             */
            lock.lock();

            long pollTimeoutMs = kafkaConsumer.getEndpoint().getConfiguration().getPollTimeoutMs();

            if (log.isTraceEnabled()) {
                log.trace("Polling {} from {} with timeout: {}", threadId, getPrintableTopic(),
                    pollTimeoutMs);
            }

            Duration pollDuration = Duration.ofMillis(pollTimeoutMs);
            // poll messages until error or wakeup exception
            while (isKafkaConsumerRunnable() && isRetrying() && isConnected()) {
                // TODO if error thrown here, no current CommitMarker will be generated
                //      and poll loop will get stuck on one message.
                //      See PDSDNREQ-7558
                Optional<RecordsBatch<Object, Object>> allRecords = consumer.poll(pollDuration);

                lastResult = processPolledRecords(
                    allRecords.orElseGet(() -> new RecordsBatch<>(Collections.emptyList(), null)),
                    kafkaRecordProcessor,
                    lastResult == null ? null : lastResult.getLeft());
            }

            if (!isConnected() && lastResult != null) {
                log.debug("Not reconnecting, check whether to auto-commit or not ...");
                commit(lastResult.getLeft().getPartitionLastOffset());
            }

        } catch (InterruptException e) {
            kafkaConsumer.getExceptionHandler()
                .handleException("Interrupted while consuming " + threadId + " from kafka topic",
                    e);
            if (lastResult != null) {
                commit(lastResult.getLeft().getPartitionLastOffset());
            }

            log.debug("Closing consumer {}", threadId);
            closeConsumer();
            Thread.currentThread().interrupt();
        } catch (WakeupException e) {
            // This is normal: it raises this exception when calling the wakeUp (which happens when we stop)

            if (log.isTraceEnabled()) {
                log.trace("The kafka consumer was woken up while polling on thread {} for {}",
                    threadId, getPrintableTopic());
            }

            log.debug("Closing consumer {}", threadId);
            closeConsumer();
        } catch (Exception e) {
            Exception originalException = e;

            // last - previously processed, current - usually has offset (last + 1)
            CommitMarker lastCommitMarker =
                lastResult != null ? lastResult.getLeft().getPartitionLastOffset() : null;
            CommitMarker currentCommitMarker = lastCommitMarker;
            if (e instanceof ProcessRecordsException processRecordsException) {
                originalException = (Exception) processRecordsException.getCause();
                currentCommitMarker = processRecordsException.getCurrentRecord().getCommitMarker();
            }

            if (log.isDebugEnabled()) {
                log.warn("Exception {} caught while polling {} from kafka {}: {}",
                    originalException.getClass().getName(), threadId, getPrintableTopic(),
                    originalException.getMessage(), originalException);
            } else {
                log.warn("Exception {} caught while polling {} from kafka {}: {}",
                    originalException.getClass().getName(), threadId, getPrintableTopic(),
                    originalException.getMessage());
            }

            // depend on PollOnError
            handleAccordingToStrategy(lastCommitMarker, currentCommitMarker, kafkaRecordProcessor,
                originalException);
        } finally {
            lock.unlock();

            // only close if not retry, for some PollOnError values and when isBreakOnErrorHit == true
            if (!isRetrying()) {
                log.debug("Closing consumer {}", threadId);
                closeConsumer();
            }
        }
    }

    private void closeConsumer() {
        if (consumer != null) {
            consumer.close();
        }
        consumerClosed = true;
    }

    private void handleAccordingToStrategy(CommitMarker lastCommitMarker,
        CommitMarker currentCommitMarker,
        KafkaBGRecordProcessor kafkaRecordProcessor, Exception e) {
        switch (pollOnError) {
            case DISCARD -> // ignore error and consume next message. NOT close consumer
                handlePollDiscard(lastCommitMarker, currentCommitMarker, kafkaRecordProcessor);
            case ERROR_HANDLER -> // handle exception and consume next message, default. NOT close consumer
                handlePollErrorHandler(lastCommitMarker, currentCommitMarker, kafkaRecordProcessor,
                    e);
            case RECONNECT -> // reconnect and retry poll same message. close consumer
                handlePollReconnect();
            case RETRY -> // retry poll same message. NOT close consumer
                handlePollRetry();
            case STOP -> // just stop consumer, not very useful. close consumer and exit from retry loop
                handlePollStop();
        }
    }

    /*
     * This is only used for presenting log messages that take into consideration that it might be subscribed to a topic
     * or a topic pattern.
     */
    private String getPrintableTopic() {
        if (topicPattern != null) {
            return "topic pattern " + topicPattern;
        } else {
            return "topic " + topicName;
        }
    }

    private void commit(CommitMarker lastOffset) {
        consumer.commitSync(lastOffset);
    }

    private void handlePollStop() {
        // stop and terminate consumer
        log.warn("Requesting the consumer to stop based on polling exception strategy");

        setRetry(false);
        setConnected(false);
    }

    private void handlePollDiscard(CommitMarker lastCommitMarker, CommitMarker currentCommitMarker,
        KafkaBGRecordProcessor kafkaRecordProcessor) {
        log.warn(
            "Requesting the consumer to discard the message and continue to the next based on polling exception strategy");

        // skip this poison message and seek to next message
        seekToNextOffset(lastCommitMarker, currentCommitMarker, kafkaRecordProcessor);
    }

    private void handlePollErrorHandler(CommitMarker lastCommitMarker,
        CommitMarker currentCommitMarker,
        KafkaBGRecordProcessor kafkaRecordProcessor, Exception e) {
        log.warn(
            "Deferring processing to the exception handler based on polling exception strategy");

        // use bridge error handler to route with exception
        bridge.handleException(e);
        // skip this poison message and seek to next message
        seekToNextOffset(lastCommitMarker, currentCommitMarker, kafkaRecordProcessor);
    }

    private void handlePollReconnect() {
        log.warn(
            "Requesting the consumer to re-connect on the next run based on polling exception strategy");

        // re-connect so the consumer can try the same message again
        setReconnect(true);
        setConnected(false);

        // to close the current consumer
        setRetry(false);
    }

    private void handlePollRetry() {
        log.warn(
            "Requesting the consumer to retry polling the same message based on polling exception strategy");

        // consumer retry the same message again
        setRetry(true);
    }

    private boolean isKafkaConsumerRunnable() {
        return kafkaConsumer.isRunAllowed() && !kafkaConsumer.isStoppingOrStopped()
            && !kafkaConsumer.isSuspendingOrSuspended();
    }

    private boolean isRunnable() {
        return kafkaConsumer.getEndpoint().getCamelContext().isStopping()
            && !kafkaConsumer.isRunAllowed();
    }

    private Pair<KafkaBGRecordProcessor.ProcessResult, Record<Object, Object>> processPolledRecords(
        RecordsBatch<Object, Object> allRecords, KafkaBGRecordProcessor kafkaRecordProcessor,
        KafkaBGRecordProcessor.ProcessResult resultFromPreviousPoll) {
        logRecords(allRecords);

        Record<Object, Object> currentRecord = null;
        try {
            KafkaBGRecordProcessor.ProcessResult lastResult
                = resultFromPreviousPoll == null
                ? KafkaBGRecordProcessor.ProcessResult.newUnprocessed() : resultFromPreviousPoll;

            List<Record<Object, Object>> records = allRecords.getBatch();
            Iterator<Record<Object, Object>> recordIterator = records.iterator();

            while (!lastResult.isBreakOnErrorHit() && recordIterator.hasNext() && !isStopping()) {
                currentRecord = recordIterator.next();
                lastResult = processRecord(lastResult, kafkaRecordProcessor, currentRecord);
            }

            if (!lastResult.isBreakOnErrorHit()) {
                log.trace("Committing offset on successful execution");
                // all records processed from partition so commit them
                kafkaRecordProcessor.commitOffset(lastResult.getPartitionLastOffset(), false,
                    false);
            }

            if (lastResult.isBreakOnErrorHit()) {
                log.debug("We hit an error ... setting flags to force reconnect");
                // force re-connect
                setReconnect(true);
                setConnected(false);
                setRetry(false); // to close the current consumer
            }

            return Pair.of(lastResult, currentRecord);
        } catch (InterruptException | WakeupException e) {
            throw e;
        } catch (Exception e) {
            throw new ProcessRecordsException(e, currentRecord);
        }
    }

    private void logRecords(RecordsBatch<Object, Object> allRecords) {
        if (log.isDebugEnabled()) {
            log.debug("Last poll on thread {} resulted on {} records to process", threadId,
                allRecords.getBatch().size());
        }
    }

    private KafkaBGRecordProcessor.ProcessResult processRecord(
        final KafkaBGRecordProcessor.ProcessResult lastResult,
        KafkaBGRecordProcessor kafkaRecordProcessor, Record<Object, Object> record) {

        logRecord(record);

        Exchange exchange = kafkaConsumer.createExchange(false);

        KafkaBGRecordProcessor.ProcessResult currentResult
            = kafkaRecordProcessor.processExchange(exchange, record, lastResult,
            kafkaConsumer.getExceptionHandler());

        // success so release the exchange
        kafkaConsumer.releaseExchange(exchange, false);

        return currentResult;
    }

    private void logRecord(Record<Object, Object> record) {
        ConsumerRecord<Object, Object> consumerRecord = record.getConsumerRecord();
        if (log.isTraceEnabled()) {
            log.trace("Partition = {}, offset = {}, key = {}, value = {}",
                consumerRecord.partition(),
                consumerRecord.offset(), consumerRecord.key(), consumerRecord.value());
        }
    }

    private KafkaBGRecordProcessor buildKafkaRecordProcessor() {
        return new KafkaBGRecordProcessor(
            kafkaConsumer.getEndpoint().getConfiguration(),
            kafkaConsumer.getProcessor(),
            consumer);
    }

    // skip message - commit (offset + 1)
    private void seekToNextOffset(CommitMarker lastCommitMarker, CommitMarker currentCommitMarker,
        KafkaBGRecordProcessor kafkaRecordProcessor) {
        if (currentCommitMarker == null) {
            // impossible state
            log.error("currentCommitMarker is null! Can't handle this");
        } else {
            kafkaRecordProcessor.commitOffset(currentCommitMarker, false, true);
        }
    }

    private boolean isRetrying() {
        return retry;
    }

    private void setRetry(boolean value) {
        retry = value;
    }

    private boolean isReconnect() {
        return reconnect;
    }

    private void setReconnect(boolean value) {
        reconnect = value;
    }

    private void setStopping(boolean value) {
        stopping.set(value);
    }

    private boolean isStopping() {
        return stopping.get();
    }

    /*
     * This wraps a safe stop procedure that should help ensure a clean termination procedure for consumer code.
     * This means that it should wait for the last process call to finish cleanly, including the commit of the
     * record being processed at the current moment.
     *
     * Note: keep in mind that the KafkaConsumer is not thread-safe, so no other call to the consumer instance
     * should be made here besides the wakeUp.
     */
    private void safeStop() {
        setStopping(true);
        long timeout = kafkaConsumer.getEndpoint().getConfiguration().getShutdownTimeout();
        try {
            /*
             Try to wait for the processing to finish before giving up and waking up the Kafka consumer regardless
             of whether the processing have finished or not.
             */
            log.info("Waiting up to {} milliseconds for the processing to finish", timeout);
            if (!lock.tryLock(timeout, TimeUnit.MILLISECONDS)) {
                log.warn("The processing of the current record did not finish within {} seconds",
                    timeout);
            }

            // As advised in the KAFKA-1894 ticket, calling this wakeup method breaks the infinite loop
            consumer.wakeup();
        } catch (InterruptedException e) {
            consumer.wakeup();
            Thread.currentThread().interrupt();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    void stop() {
        safeStop();
    }

    void shutdown() {
        safeStop();
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }
}
