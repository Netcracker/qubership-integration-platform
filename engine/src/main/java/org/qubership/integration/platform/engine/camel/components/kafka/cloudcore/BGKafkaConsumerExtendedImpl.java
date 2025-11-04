package org.qubership.integration.platform.engine.camel.components.kafka.cloudcore;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import com.netcracker.cloud.bluegreen.api.model.BlueGreenState;
import com.netcracker.cloud.bluegreen.api.model.NamespaceVersion;
import com.netcracker.cloud.bluegreen.api.model.State;
import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import com.netcracker.cloud.maas.bluegreen.kafka.CommitMarker;
import com.netcracker.cloud.maas.bluegreen.kafka.Record;
import com.netcracker.cloud.maas.bluegreen.kafka.RecordsBatch;
import com.netcracker.cloud.maas.bluegreen.kafka.impl.*;
import com.netcracker.cloud.maas.bluegreen.versiontracker.impl.VersionFilterConstructor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG;
import static com.netcracker.cloud.framework.contexts.xversion.XVersionContextObject.X_VERSION_SERIALIZATION_NAME;

/**
 * Based on {@link com.netcracker.cloud.maas.bluegreen.kafka.impl.BGKafkaConsumerImpl}
 * <p>
 *
 * Difference with BGKafkaConsumerImpl:
 * <p>1) BlueGreenStatePublisher does not close when consumer disconnects
 * <p>2) Implement methods from BGKafkaConsumerExtended
 *
 * <p>
 * Warning: it is not thread safe
 */
@Slf4j
public class BGKafkaConsumerExtendedImpl<K, V> implements BGKafkaConsumerExtended<K, V> {
    private final BGKafkaConsumerConfig config;
    private final BlueGreenStatePublisher blueGreenStatePublisher;

    @Getter
    private Consumer<K, V> kafkaConsumer;
    private Predicate<String> filter = v -> true;

    private BlueGreenState activeState;
    @Setter
    private Runnable onCloseCallback = () -> {};
    private final AtomicReference<BlueGreenState> bgStateRef = new AtomicReference<>();

    private final java.util.function.Consumer<BlueGreenState> stateListenerCallback;

    public BGKafkaConsumerExtendedImpl(BGKafkaConsumerConfig config) {
        this.config = config;

        this.blueGreenStatePublisher = config.getStatePublisher();

        CompletableFuture<Void> awaitState = new CompletableFuture<>();
        this.stateListenerCallback = (state) -> {
            log.info("Receive BG state from mesh (kafka consumer): {}", state);
            bgStateRef.set(state);
            awaitState.complete(null);
        };
        bgStateRef.set(blueGreenStatePublisher.getBlueGreenState());
        blueGreenStatePublisher.subscribe(stateListenerCallback);

        if (bgStateRef.get() != null) {
            log.info("Initial BG state (kafka consumer): {}",
                bgStateRef.get().getCurrent().getState().getName());
            awaitState.complete(null);
        }

        try {
            awaitState.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Consumer initialization error: timeout getting BG state");
        }
    }

    @Override
    @SuppressWarnings("checkstyle:EmptyCatchBlock")
    public Optional<RecordsBatch<K, V>> poll(Duration timeout) {
        var bgState = bgStateRef.getAndSet(null);
        if (bgState != null) {
            if (bgState.getCurrent().getState() == State.IDLE) {
                // the namespace we are in received IDLE state, it means we are being cleaned up via commit operation
                log.warn("Current namespace is in IDLE state. Waiting for duration of specified timeout = {} and returning empty result.", timeout);
                try {
                    TimeUnit.SECONDS.sleep(timeout.toSeconds());
                } catch (InterruptedException ignored) {
                }
                return Optional.empty();
            }
            try {
                this.activeState = reinitializeConsumerIfNeeded(bgState);
            } catch (Exception e) {
                log.error("error initializing consumer for new state: {}", bgState);

                // we didn't finished initialization, so return bgState value to marker
                bgStateRef.compareAndSet(null, bgState);

                // inform user about exceptional state
                throw e;
            }
        }

        Objects.requireNonNull(activeState);

        ConsumerRecords<K, V> records = kafkaConsumer.poll(timeout);
        if (records.count() > 0) {
            List<Record<K, V>> result = new ArrayList<>(records.count());

            var position = new HashMap<TopicPartition, OffsetAndMetadata>();
            CommitMarker marker = null;
            for (ConsumerRecord<K, V> record : records) {
                String xVersion = extractVersionHeader(record);

                log.debug("Record's topic: {}, partition: {}, offset: {}, header: {}='{}'",
                        record.topic(), record.partition(), record.offset(), X_VERSION_SERIALIZATION_NAME, xVersion);

                position.put(
                    new TopicPartition(record.topic(), record.partition()),
                    new OffsetAndMetadata(record.offset() + 1)
                );

                // create marker with clone of position
                marker = new CommitMarker(activeState.getCurrent(), new HashMap<>(position));

                if (config.isIgnoreFilter() || filter.test(xVersion)) {
                    log.debug("Accept consumer record: {}", record);
                    result.add(new Record<>(record, marker));
                } else {
                    log.debug("Skip message due to unmatched header ({}='{}')", X_VERSION_SERIALIZATION_NAME, xVersion);
                }
            }
            return Optional.of(new RecordsBatch<>(result, marker));
        } else {
            return Optional.empty();
        }
    }

    @Override
    public void commitSync(CommitMarker marker) {
        if (activeState.getCurrent().equals(marker.getVersion())) {
            log.debug("Committing marker: {}", marker);
            kafkaConsumer.commitSync(marker.getPosition());
        } else {
            log.warn("Skip commits for non active consumer for: {}", marker.getVersion());
        }
    }

    @Override
    public void commitSync(CommitMarker marker, Duration timeout) {
        if (activeState.getCurrent().equals(marker.getVersion())) {
            kafkaConsumer.commitSync(marker.getPosition(), timeout);
        } else {
            log.warn("Skip commits for non active consumer for: {}", marker.getVersion());
        }
    }

    @Override
    public void wakeup() {
        kafkaConsumer.wakeup();
    }

    @SneakyThrows
    public void close() {
        log.info("Closing consumer");
        blueGreenStatePublisher.unsubscribe(stateListenerCallback);
        onCloseCallback.run();
        Optional.ofNullable(kafkaConsumer).ifPresent(Consumer::close);
    }

    @Override
    public void pause() {
        Optional.ofNullable(kafkaConsumer).ifPresentOrElse(c -> c.pause(assignment()), () -> {
            throw new IllegalStateException("Consumer not initiated yet. Initiate with poll()");
        });
    }

    @Override
    public void resume() {
        Optional.ofNullable(kafkaConsumer).ifPresentOrElse(c -> c.resume(assignment()), () -> {
            throw new IllegalStateException("Consumer not initiated yet. Initiate with poll()");
        });
    }

    @Override
    public Set<TopicPartition> paused() {
        return Optional.ofNullable(kafkaConsumer).map(Consumer::paused)
                .orElseThrow(() -> new IllegalStateException("Consumer not initiated yet. Initiate with poll()"));
    }

    @Override
    public Collection<TopicPartition> assignment() {
        return Optional.ofNullable(kafkaConsumer).map(Consumer::assignment)
                .orElseThrow(() -> new IllegalStateException("Consumer not initiated yet. Initiate with poll()"));
    }

    private BlueGreenState reinitializeConsumerIfNeeded(BlueGreenState bgState) {
        log.info("Initialize consumer for {}", bgState);
        if (kafkaConsumer != null) {
            try {
                kafkaConsumer.close();
            } catch (Exception e) {
                // ignore this exception, because one crashed consumer doesn't affect to new one
                log.error("Error closing consumer", e);
            }
        }

        var current = bgState.getCurrent();
        var siblingNsState = bgState.getSibling().map(NamespaceVersion::getState);
        GroupId groupId;
        if (siblingNsState.isEmpty()) {
            groupId = new PlainGroupId(config.getGroupIdPrefix());
        } else {
            groupId = new VersionedGroupId(config.getGroupIdPrefix(), current.getVersion(), current.getState(), siblingNsState.get(), bgState.getUpdateTime());
        }

        var props = new HashMap<>(config.getProperties());
        // override group id to versioned one
        props.put(GROUP_ID_CONFIG, groupId.toString());
        if (props.get(AUTO_OFFSET_RESET_CONFIG) != null) {
            log.warn("`{}' is not suitable with BGKafkaConsumer usage. Please switch to use OffsetSetupStrategy enum. Ignored.", AUTO_OFFSET_RESET_CONFIG);
            props.remove(AUTO_OFFSET_RESET_CONFIG);
        }

        log.debug("Construct new instance of kafka consumer for groupId: '{}'", groupId);
        kafkaConsumer = config.getConsumerSupplier().apply(props);

        // use rebalance listener to perform offsets alignment only when our consumer was rebalanced by kafka
        ConsumerRebalanceListener rebalanceListener = new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                log.debug("Partitions were revoked: {}", partitions);
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                log.debug("Partitions were assigned: {}", partitions);
                log.debug("Start offset alignment process for: {}", groupId);
                OffsetsManager offsetsManager = new OffsetsManager(config, kafkaConsumer);
                Map<TopicPartition, OffsetAndMetadata> alignedOffsets = offsetsManager.alignOffset(groupId)
                        .entrySet().stream()
                        .filter(entry -> partitions.contains(entry.getKey()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                log.debug("Adjusting current consumer offsets to: {}", alignedOffsets);
                alignedOffsets.forEach(((topicPartition, offsetAndMetadata) -> kafkaConsumer.seek(topicPartition, offsetAndMetadata)));
            }
        };
        log.debug("Subscribing kafka consumer to the topics: {}", config.getTopics().stream().sorted().toList());
        kafkaConsumer.subscribe(config.getTopics(), rebalanceListener);

        // update filter according to BG state
        filter = VersionFilterConstructor.constructVersionFilter(bgState);
        log.info("Version filter updated to: {}", filter);
        return bgState;
    }

    @Override
    public Map<MetricName, ? extends Metric> metrics() {
        if (kafkaConsumer == null) {
            log.debug("Can't get kafka metrics, kafkaConsumer not initialized now");
            return Collections.emptyMap();
        } else {
            log.debug("Get kafka metrics");
            return kafkaConsumer.metrics();
        }
    }

    private String extractVersionHeader(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(X_VERSION_SERIALIZATION_NAME);
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }
}
