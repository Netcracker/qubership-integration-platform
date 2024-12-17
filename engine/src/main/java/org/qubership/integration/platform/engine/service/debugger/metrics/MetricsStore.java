/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.service.debugger.metrics;

import com.google.common.collect.Maps;
import org.qubership.integration.platform.engine.configuration.ServerConfiguration;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.persistence.shared.entity.ChainDataAllocationSize;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.BaseUnits;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stores metrics
 */
@Slf4j
@Component
public class MetricsStore {
    private static final String SESSION_TIMER_NAME = "sessions.duration.timer";
    private static final String SESSIONS_COUNTER_NAME = "sessions.counter";
    private static final String CHAINS_FAILURES_COUNTER_NAME = "chains.failures";

    private static final String SYSTEM_RESPONSE_CODE_NAME = "system.response.code";

    private static final String CIRCUIT_BREAKER_EXECUTION_NAME = "elements.circuitbreaker.execution";
    private static final String CIRCUIT_BREAKER_EXECUTION_FALLBACK_NAME = "elements.circuitbreaker.execution.fallback";

    private static final String HTTP_TRIGGER_REQUEST_PAYLOAD_SIZE_NAME = "http.trigger.request.payload.size";
    private static final String HTTP_TRIGGER_RESPONSE_PAYLOAD_SIZE_NAME = "http.trigger.response.payload.size";
    private static final String HTTP_SENDERS_REQUEST_PAYLOAD_SIZE_NAME = "http.senders.request.payload.size";
    private static final String HTTP_SENDERS_RESPONSE_PAYLOAD_SIZE_NAME = "http.senders.response.payload.size";

    private static final String CHAINS_DEPLOYMENTS_NAME = "chains.deployments";
    private static final String CHAIN_SESSION_SIZE = "chain.session.size";
    private static final String CHAIN_CHECKPOINT_SIZE = "chain.checkpoint.size";

    private static final String EXECUTION_STATUS_TAG = "execution_status";
    private static final String CHAIN_STATUS_CODE_TAG = "chain_status_code";
    private static final String CHAIN_STATUS_REASON_TAG = "chain_status_reason";
    private static final String SNAPSHOT_NAME_TAG = "snapshot_name";
    public static final String CHAIN_ID_TAG = "chain_id";
    public static final String CHAIN_NAME_TAG = "chain_name";
    public static final String ELEMENT_ID_TAG = "element_id";
    public static final String ELEMENT_NAME_TAG = "element_name";
    public static final String ELEMENT_TYPE_TAG = "element_type";
    public static final String ENGINE_DOMAIN_TAG = "engine_domain";
    private static final String RESPONSE_CODE_TAG = "response_code";

    public static final String MAAS_CLASSIFIER = "maas_classifier";

    private final String namePrefix;

    private static final int CHAINS_DEPLOYMENTS_NUMBER = 1;

    @Value("${qip.metrics.prometheus.init.delay}")
    private long lagDelay;

    @Getter
    @Value("${qip.metrics.enabled}")
    private boolean metricsEnabled;

    @Getter
    @Value("${qip.metrics.http-payload-metrics.enabled}")
    private boolean httpPayloadMetricsEnabled;

    @Getter
    @Value("${qip.metrics.http-payload-metrics.buckets}")
    private double[] httpPayloadMetricsBuckets;

    @Getter
    private final MeterRegistry meterRegistry;

    // <chainId__chainName, <responseCode, counter>>
    private final ConcurrentMap<String, ConcurrentMap<String, CounterWrapper>> responseCodeCounters;

    // <chainId__chainName, <session_status, timer>>
    private final ConcurrentMap<String, ConcurrentMap<String, Timer>> sessionExecutionTime;

    // <chainId__chainName, <session_status, counter>>
    private final ConcurrentMap<String, ConcurrentMap<String, CounterWrapper>> sessionsCounters;

    // <chainId__chainName, <elementId, counter>>
    private final ConcurrentMap<String, ConcurrentMap<String, CounterWrapper>> circuitBreakerExecutionCounters;

    // <chainId__chainName, <elementId, counter>>
    private final ConcurrentMap<String, ConcurrentMap<String, CounterWrapper>> circuitBreakerExecutionFallbackCounters;

    // <chainId__chainName, <errorCode, counter>>
    private final ConcurrentMap<String, ConcurrentMap<ErrorCode, CounterWrapper>> chainsFailuresCounters;

    // <chainId__chainName, <deploymentId, gauge>>
    private final ConcurrentMap<String, ConcurrentMap<String, Gauge>> chainsDeploymentsGauges;

    // <chainId__chainName, <elementId, distributionSummary>>
    private final ConcurrentMap<String, ConcurrentMap<String, DistributionSummary>> httpPayloadSizeDistributionSummary;

    // <chainId__chainName, <AtomicLong (Gauge reference) >
    private final ConcurrentMap<String, AtomicLong> sessionSizeGauges;

    // <chainId__chainName, <AtomicLong (Gauge reference) >
    private final ConcurrentMap<String, AtomicLong> checkpointsSizeGauges;

    private final ServerConfiguration serverConfiguration;

    @Autowired
    public MetricsStore(ServerConfiguration serverConfiguration, MeterRegistry meterRegistry,
                        @Value("${app.prefix}") String appPrefix) {
        this.serverConfiguration = serverConfiguration;
        this.meterRegistry = meterRegistry;
        this.sessionExecutionTime = Maps.newConcurrentMap();
        this.sessionsCounters = Maps.newConcurrentMap();
        this.responseCodeCounters = Maps.newConcurrentMap();
        this.circuitBreakerExecutionCounters = Maps.newConcurrentMap();
        this.circuitBreakerExecutionFallbackCounters = Maps.newConcurrentMap();
        this.chainsFailuresCounters = Maps.newConcurrentMap();
        this.chainsDeploymentsGauges = Maps.newConcurrentMap();
        this.httpPayloadSizeDistributionSummary = Maps.newConcurrentMap();
        this.sessionSizeGauges = Maps.newConcurrentMap();
        this.checkpointsSizeGauges = Maps.newConcurrentMap();
        this.namePrefix = appPrefix + ".engine.";
    }

    public void processSessionFinish(String chainId, String chainName, String status, long duration) {
        if (metricsEnabled) {
            ConcurrentMap<String, Timer> timerMap = sessionExecutionTime.computeIfAbsent(buildChainMapKey(chainId, chainName), id -> Maps.newConcurrentMap());
            Timer timer = timerMap.computeIfAbsent(status, executionStatus -> newSessionDurationTimer(chainId, chainName, executionStatus));
            timer.record(duration, TimeUnit.MILLISECONDS);

            ConcurrentMap<String, CounterWrapper> counterMap = sessionsCounters.computeIfAbsent(buildChainMapKey(chainId, chainName), id -> Maps.newConcurrentMap());
            CounterWrapper sessionsCounter = counterMap.computeIfAbsent(status, executionStatus -> {
                CounterWrapper counterWrapper = new CounterWrapper(newCounter(chainId, chainName, executionStatus));
                Executors.newSingleThreadScheduledExecutor().schedule(counterWrapper::commitAndUnlock, lagDelay, TimeUnit.SECONDS);
                return counterWrapper;
            });

            sessionsCounter.increment();
        }
    }

    public void processChainFailure(String chainId, String chainName, ErrorCode errorCode) {
        if (metricsEnabled) {
            ConcurrentMap<ErrorCode, CounterWrapper> chainFailuresCounterMap = chainsFailuresCounters
                    .computeIfAbsent(buildChainMapKey(chainId, chainName), id -> Maps.newConcurrentMap());
            CounterWrapper chainFailureCounter = chainFailuresCounterMap.computeIfAbsent(errorCode, currentErrorCode -> {
                CounterWrapper counterWrapper = new CounterWrapper(newChainsFailuresCounter(chainId, chainName, currentErrorCode));
                Executors.newSingleThreadScheduledExecutor().schedule(counterWrapper::commitAndUnlock, lagDelay, TimeUnit.SECONDS);
                return counterWrapper;
            });
            chainFailureCounter.increment();
        }
    }

    public void processHttpResponseCode(String chainId, String chainName, String responseCode) {
        if (metricsEnabled) {
            ConcurrentMap<String, CounterWrapper> chainResponseCodeMap = responseCodeCounters.computeIfAbsent(
                    buildChainMapKey(chainId, chainName),
                    id -> Maps.newConcurrentMap());

            CounterWrapper responseCounter = chainResponseCodeMap.computeIfAbsent(responseCode, executionStatus -> {
                CounterWrapper counterWrapper =
                        new CounterWrapper(newResponseCodeCounter(chainId, chainName, responseCode));
                Executors.newSingleThreadScheduledExecutor().schedule(counterWrapper::commitAndUnlock, lagDelay, TimeUnit.SECONDS);
                return counterWrapper;
            });

            responseCounter.increment();
        }
    }

    public void processCircuitBreakerExecution(String chainId, String chainName, String elementId, String elementName) {
        if (metricsEnabled) {
            ConcurrentMap<String, CounterWrapper> cbExecutionMap = circuitBreakerExecutionCounters.computeIfAbsent(
                    buildChainMapKey(chainId, chainName),
                    id -> Maps.newConcurrentMap());

            CounterWrapper responseCounter = cbExecutionMap.computeIfAbsent(elementId, executionStatus -> {
                CounterWrapper counterWrapper =
                        new CounterWrapper(newCircuitBreakerExecutionCounter(chainId, chainName, elementId, elementName));
                Executors.newSingleThreadScheduledExecutor().schedule(counterWrapper::commitAndUnlock, lagDelay, TimeUnit.SECONDS);
                return counterWrapper;
            });

            responseCounter.increment();
        }
    }

    public void processCircuitBreakerExecutionFallback(String chainId, String chainName, String elementId, String elementName) {
        if (metricsEnabled) {
            ConcurrentMap<String, CounterWrapper> cbExecutionFallbackMap = circuitBreakerExecutionFallbackCounters.computeIfAbsent(
                    buildChainMapKey(chainId, chainName),
                    id -> Maps.newConcurrentMap());

            CounterWrapper responseCounter = cbExecutionFallbackMap.computeIfAbsent(elementId, executionStatus -> {
                CounterWrapper counterWrapper =
                        new CounterWrapper(newCircuitBreakerExecutionFallbackCounter(chainId, chainName, elementId, elementName));
                Executors.newSingleThreadScheduledExecutor().schedule(counterWrapper::commitAndUnlock, lagDelay, TimeUnit.SECONDS);
                return counterWrapper;
            });

            responseCounter.increment();
        }
    }

    public void processChainsDeployments(String deploymentId, String chainId, String chainName, String executionStatus, String chainStatusCode, String snapshotName) {
        if (metricsEnabled) {
            ConcurrentMap<String, Gauge> chainsInfoMap = chainsDeploymentsGauges.computeIfAbsent(
                    deploymentId,
                    id -> Maps.newConcurrentMap());

            chainsInfoMap.computeIfAbsent(deploymentId, computedExecutionStatus -> newChainsDeploymentsGauge(chainId, chainName, executionStatus, chainStatusCode, snapshotName));
        }
    }

    public DistributionSummary processHttpPayloadSize(boolean isRequest, String chainId, String chainName, String elementId, String elementName, String elementType) {
        if (metricsEnabled && httpPayloadMetricsEnabled) {
            ConcurrentMap<String, DistributionSummary> httpPayloadSizeMap = httpPayloadSizeDistributionSummary.computeIfAbsent(
                    buildChainMapKey(chainId, chainName),
                    id -> Maps.newConcurrentMap());

            String payloadType = isRequest ? "_request" : "_response";

            return httpPayloadSizeMap.computeIfAbsent(elementId + payloadType, computedDistributionSummary -> newHttpPayloadSizeDistributionSummary(isRequest, chainId, chainName, elementId, elementName, elementType));
        }
        return null;
    }

    public void processChainSessionsSize(List<ChainDataAllocationSize> chainSessionsSizes) {
        if (metricsEnabled) {
            processChainDataAllocationSize(namePrefix + CHAIN_SESSION_SIZE, sessionSizeGauges, chainSessionsSizes);
        }
    }

    public void processChainCheckpointsSize(List<ChainDataAllocationSize> chainCheckpointSizes) {
        if (metricsEnabled) {
            processChainDataAllocationSize(namePrefix + CHAIN_CHECKPOINT_SIZE, checkpointsSizeGauges, chainCheckpointSizes);
        }
    }

    public void removeChainsDeployments(String deploymentId) {
        if (metricsEnabled) {
            for (Map.Entry<String, ConcurrentMap<String, Gauge>> chainsDeploymentGauge : chainsDeploymentsGauges.entrySet()) {
                if (deploymentId.equals(chainsDeploymentGauge.getKey())) {
                    for (Map.Entry<String, Gauge> gauge : chainsDeploymentGauge.getValue().entrySet()) {
                        if (deploymentId.equals(gauge.getKey())) {
                            meterRegistry.remove(gauge.getValue());
                        }
                    }
                }
            }
        }
    }

    private Timer newSessionDurationTimer(String chainId, String chainName, String executionStatus) {
        return Timer.builder(namePrefix + SESSION_TIMER_NAME)
                .publishPercentileHistogram()
                .tag(CHAIN_ID_TAG, chainId)
                .tag(CHAIN_NAME_TAG, chainName)
                .tag(EXECUTION_STATUS_TAG, executionStatus)
                .tag(ENGINE_DOMAIN_TAG, serverConfiguration.getDomain())
                .register(meterRegistry);
    }

    private Counter newCounter(String chainId, String chainName, String executionStatus) {
        return Counter.builder(namePrefix + SESSIONS_COUNTER_NAME)
                .tag(CHAIN_ID_TAG, chainId)
                .tag(CHAIN_NAME_TAG, chainName)
                .tag(EXECUTION_STATUS_TAG, executionStatus)
                .tag(ENGINE_DOMAIN_TAG, serverConfiguration.getDomain())
                .register(meterRegistry);
    }

    private Counter newResponseCodeCounter(String chainId, String chainName, String responseCode) {
        return Counter.builder(namePrefix + SYSTEM_RESPONSE_CODE_NAME)
                .tag(CHAIN_ID_TAG, chainId)
                .tag(CHAIN_NAME_TAG, chainName)
                .tag(RESPONSE_CODE_TAG, responseCode)
                .tag(ENGINE_DOMAIN_TAG, serverConfiguration.getDomain())
                .register(meterRegistry);
    }

    private Counter newCircuitBreakerExecutionCounter(String chainId, String chainName, String elementId, String elementName) {
        return Counter.builder(namePrefix + CIRCUIT_BREAKER_EXECUTION_NAME)
                .tag(CHAIN_ID_TAG, chainId)
                .tag(CHAIN_NAME_TAG, chainName)
                .tag(ELEMENT_ID_TAG, elementId)
                .tag(ELEMENT_NAME_TAG, elementName)
                .tag(ENGINE_DOMAIN_TAG, serverConfiguration.getDomain())
                .register(meterRegistry);
    }

    private Counter newCircuitBreakerExecutionFallbackCounter(String chainId, String chainName, String elementId, String elementName) {
        return Counter.builder(namePrefix + CIRCUIT_BREAKER_EXECUTION_FALLBACK_NAME)
                .tag(CHAIN_ID_TAG, chainId)
                .tag(CHAIN_NAME_TAG, chainName)
                .tag(ELEMENT_ID_TAG, elementId)
                .tag(ELEMENT_NAME_TAG, elementName)
                .tag(ENGINE_DOMAIN_TAG, serverConfiguration.getDomain())
                .register(meterRegistry);
    }

    private Counter newChainsFailuresCounter(String chainId, String chainName, ErrorCode errorCode) {
        return Counter.builder(namePrefix + CHAINS_FAILURES_COUNTER_NAME)
                .tag(CHAIN_ID_TAG, chainId)
                .tag(CHAIN_NAME_TAG, chainName)
                .tag(CHAIN_STATUS_CODE_TAG, errorCode.getCode())
                .tag(CHAIN_STATUS_REASON_TAG, errorCode.getPayload().getReason())
                .tag(ENGINE_DOMAIN_TAG, serverConfiguration.getDomain())
                .register(meterRegistry);
    }

    private Gauge newChainsDeploymentsGauge(String chainId, String chainName, String executionStatus, String chainStatusCode, String snapshotName) {
        return Gauge.builder(namePrefix + CHAINS_DEPLOYMENTS_NAME, () -> CHAINS_DEPLOYMENTS_NUMBER)
                .tag(CHAIN_ID_TAG, chainId)
                .tag(CHAIN_NAME_TAG, chainName)
                .tag(EXECUTION_STATUS_TAG, executionStatus)
                .tag(CHAIN_STATUS_CODE_TAG, chainStatusCode)
                .tag(SNAPSHOT_NAME_TAG, snapshotName)
                .tag(ENGINE_DOMAIN_TAG, serverConfiguration.getDomain())
                .register(meterRegistry);
    }

    private DistributionSummary newHttpPayloadSizeDistributionSummary(boolean isRequest, String chainId, String chainName, String elementId, String elementName, String elementType) {
        String metricName = switch (ChainElementType.fromString(elementType)) {
            case ChainElementType.HTTP_TRIGGER -> {
                if (isRequest) {
                    yield namePrefix + HTTP_TRIGGER_REQUEST_PAYLOAD_SIZE_NAME;
                } else {
                    yield namePrefix + HTTP_TRIGGER_RESPONSE_PAYLOAD_SIZE_NAME;
                }
            }
            case ChainElementType.HTTP_SENDER, ChainElementType.SERVICE_CALL -> {
                if (isRequest) {
                    yield namePrefix + HTTP_SENDERS_REQUEST_PAYLOAD_SIZE_NAME;
                } else {
                    yield namePrefix + HTTP_SENDERS_RESPONSE_PAYLOAD_SIZE_NAME;
                }
            }
            default -> "";
        };

        return DistributionSummary.builder(metricName)
                .tag(CHAIN_ID_TAG, chainId)
                .tag(CHAIN_NAME_TAG, chainName)
                .tag(ELEMENT_ID_TAG, elementId)
                .tag(ELEMENT_NAME_TAG, elementName)
                .tag(ELEMENT_TYPE_TAG, elementType)
                .tag(ENGINE_DOMAIN_TAG, serverConfiguration.getDomain())
                .distributionStatisticExpiry(Duration.ofMinutes(1))
                .baseUnit(BaseUnits.BYTES)
                .serviceLevelObjectives(httpPayloadMetricsBuckets)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }


    /**
     * Create/Update Gauge metrics for :
     *<ul>
     *   <li>Chain sessions size stored in Opensearch</li>
     *   <li>Checkpoint context saved data in engine PostgreSQL DB </li>
     *</ul>
     * For chains not presented in current inbound date metric value should be set to 0
     * @param metricName cip_engine_chain_session_size | cip_engine_chain_checkpoint_size
     * @param metricMap gauge references map chainId__chainName, AtomicLong (Gauge reference)
     * @param chainDataAllocationSizes current chains measurements
     */
    private void processChainDataAllocationSize(String metricName, ConcurrentMap<String, AtomicLong> metricMap, List<ChainDataAllocationSize> chainDataAllocationSizes) {
            List<String> inboundChainMapKeys = new ArrayList<>();

            chainDataAllocationSizes.forEach(chainAllocationSize -> {
                String chainMapKey = buildChainMapKey(chainAllocationSize.getChainId(), chainAllocationSize.getChainName());
                inboundChainMapKeys.add(chainMapKey);
                if (metricMap.getOrDefault(chainMapKey, null) != null) {
                    //Update existing gauge for chain via reference
                    metricMap.get(chainMapKey).set(chainAllocationSize.getAllocatedSize());
                } else {
                    //Register new gauge for chain
                    Tag chainIdTag = Tag.of(CHAIN_ID_TAG, chainAllocationSize.getChainId());
                    Tag chainNameTag = Tag.of(CHAIN_NAME_TAG, chainAllocationSize.getChainName());
                    metricMap.put(chainMapKey, meterRegistry.gauge(metricName, List.of(chainIdTag, chainNameTag), new AtomicLong(chainAllocationSize.getAllocatedSize())));
                }
            });

            //Set gauge value to 0 for all chains not presented in current measurement
            List<String> chainMapKeysToReset = metricMap.keySet()
                    .stream()
                    .filter(chainMapKey -> !inboundChainMapKeys.contains(chainMapKey))
                    .toList();
            chainMapKeysToReset.forEach(chainMapKey -> metricMap.get(chainMapKey).set(0));
    }

    private String buildChainMapKey(String chainId, String chainName) {
        return chainId + "__" + chainName;
    }

    @Getter
    @Setter
    private static class CounterWrapper {
        private Counter counter;
        private final AtomicBoolean lock;
        private int count;

        public CounterWrapper(Counter counter) {
            this.counter = counter;
            counter.increment(0);
            lock = new AtomicBoolean(true);
            count = 0;
        }

        public void commitAndUnlock() {
            synchronized (lock) {
                counter.increment(count);
                lock.set(false);
            }
        }

        public void increment() {
            synchronized (lock) {
                if (lock.get()) {
                    count++;
                } else {
                    counter.increment();
                }
            }
        }
    }
}
