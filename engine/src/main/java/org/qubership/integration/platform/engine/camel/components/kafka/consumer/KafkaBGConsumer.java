package org.qubership.integration.platform.engine.camel.components.kafka.consumer;

import com.netcracker.cloud.maas.bluegreen.kafka.ConsumerConsistencyMode;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Processor;
import org.apache.camel.component.kafka.KafkaConsumer;
import org.apache.camel.spi.StateRepository;
import org.apache.camel.support.BridgeExceptionHandlerToErrorHandler;
import org.apache.camel.support.DefaultConsumer;
import org.apache.camel.support.service.ServiceHelper;
import org.apache.camel.support.service.ServiceSupport;
import org.apache.camel.util.ObjectHelper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaCustomEndpoint;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Based on {@link KafkaConsumer}.
 * Replaced endpoint, config and fetch record classes to support blue-green.
 */
@Slf4j
public class KafkaBGConsumer extends DefaultConsumer {

    protected ExecutorService executor;
    private final KafkaCustomEndpoint endpoint;
    // This list helps to work around the infinite loop of KAFKA-1894
    private final List<KafkaBGFetchRecords> tasks = new ArrayList<>();
    private volatile boolean stopOffsetRepo;

    public KafkaBGConsumer(KafkaCustomEndpoint endpoint, Processor processor) {
        super(endpoint, processor);
        this.endpoint = endpoint;
    }

    @Override
    protected void doBuild() throws Exception {
        super.doBuild();
    }

    @Override
    public KafkaCustomEndpoint getEndpoint() {
        return (KafkaCustomEndpoint) super.getEndpoint();
    }

    private String randomUUID() {
        return UUID.randomUUID().toString();
    }

    private Properties getProps() {
        KafkaCustomConfiguration configuration = endpoint.getConfiguration();

        Properties props = configuration.createConsumerProperties();
        endpoint.updateClassProperties(props);

        ObjectHelper.ifNotEmpty(endpoint.getKafkaClientFactory().getBrokers(configuration),
            v -> props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, v));

        String groupId = ObjectHelper.supplyIfEmpty(configuration.getGroupId(), this::randomUUID);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);

        ObjectHelper.ifNotEmpty(configuration.getGroupInstanceId(),
            v -> props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, v));

        return props;
    }

    @Override
    protected void doStart() throws Exception {
        log.info("Starting Kafka consumer on topic: {} with breakOnFirstError: {}",
            endpoint.getConfiguration().getTopic(),
            endpoint.getConfiguration().isBreakOnFirstError());
        super.doStart();

        // is the offset repository already started?
        StateRepository<String, String> repo = endpoint.getConfiguration().getOffsetRepository();
        if (repo instanceof ServiceSupport) {
            boolean started = ((ServiceSupport) repo).isStarted();
            // if not already started then we would do that and also stop it
            if (!started) {
                stopOffsetRepo = true;
                log.debug("Starting OffsetRepository: {}", repo);
                ServiceHelper.startService(endpoint.getConfiguration().getOffsetRepository());
            }
        }

        executor = endpoint.createExecutor();

        String topic = endpoint.getConfiguration().getTopic();
        Pattern pattern = null;
        if (endpoint.getConfiguration().isTopicIsPattern()) {
            pattern = Pattern.compile(topic);
        }

        BridgeExceptionHandlerToErrorHandler bridge = new BridgeExceptionHandlerToErrorHandler(this);
        for (int i = 0; i < endpoint.getConfiguration().getConsumersCount(); i++) {
            KafkaBGFetchRecords task = new KafkaBGFetchRecords(
                this, bridge, topic, pattern, Integer.toString(i), getProps(),
                getConsistencyMode(), endpoint.getConfiguration().getPollOnError());
            executor.submit(task);

            tasks.add(task);
        }
    }

    @Override
    protected void doStop() throws Exception {
        if (endpoint.getConfiguration().isTopicIsPattern()) {
            log.info("Stopping Kafka consumer on topic pattern: {}", endpoint.getConfiguration().getTopic());
        } else {
            log.info("Stopping Kafka consumer on topic: {}", endpoint.getConfiguration().getTopic());
        }

        if (executor != null) {
            if (getEndpoint() != null && getEndpoint().getCamelContext() != null) {
                // signal kafka consumer to stop
                for (KafkaBGFetchRecords task : tasks) {
                    task.stop();
                }
                int timeout = getEndpoint().getConfiguration().getShutdownTimeout();
                log.debug("Shutting down Kafka consumer worker threads with timeout {} millis", timeout);
                getEndpoint().getCamelContext().getExecutorServiceManager()
                    .shutdownGraceful(executor, timeout);
            } else {
                executor.shutdown();

                int timeout = endpoint.getConfiguration().getShutdownTimeout();
                log.debug("Shutting down Kafka consumer worker threads with timeout {} millis", timeout);
                if (!executor.awaitTermination(timeout, TimeUnit.MILLISECONDS)) {
                    log.warn(
                        "Shutting down Kafka {} consumer worker threads did not finish within {} millis",
                        tasks.size(), timeout);
                }
            }

            if (!executor.isTerminated()) {
                tasks.forEach(KafkaBGFetchRecords::shutdown);
                executor.shutdownNow();
            }
        }
        tasks.clear();
        executor = null;

        if (stopOffsetRepo) {
            StateRepository<String, String> repo = endpoint.getConfiguration()
                .getOffsetRepository();
            log.debug("Stopping OffsetRepository: {}", repo);
            ServiceHelper.stopAndShutdownService(repo);
        }

        super.doStop();
    }

    // blue-green consistency mode
    private ConsumerConsistencyMode getConsistencyMode() {
        String consumerConsistencyMode = endpoint.getConfiguration().getConsumerConsistencyMode();
        try {
            return ConsumerConsistencyMode.valueOf(consumerConsistencyMode);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Invalid enum value for consumerConsistencyMode property: ["
                    + consumerConsistencyMode + "]."
                    + " Allowed: [EVENTUAL, GUARANTEE_CONSUMPTION]", e);
        }
    }
}
