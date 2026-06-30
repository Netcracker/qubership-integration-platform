package org.qubership.integration.platform.engine.service.debugger.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;
import org.qubership.integration.platform.engine.persistence.shared.entity.ChainDataAllocationSize;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MetricsStoreTest {

    private static final String APP_PREFIX = "qip";
    private static final String ENGINE_DOMAIN = "test-domain";
    private static final String CHAIN_ID = "7b990a0c-5da8-4b3e-88d0-1e6d1727756d";
    private static final String CHAIN_NAME = "Test chain";
    private static final String ELEMENT_ID = "036b4a52-9613-43ab-a521-bd25ece5d382";
    private static final String ELEMENT_NAME = "Script";
    private static final String DEPLOYMENT_ID = "d797ce4e-9c68-4bdf-9540-7d397ac0923a";

    private MetricsStore metricsStore;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metricsStore = new MetricsStore(
            EngineInfo.builder().domain(ENGINE_DOMAIN).build(),
            meterRegistry,
            APP_PREFIX
        );
        metricsStore.httpPayloadMetricsBuckets = new double[]{100.0, 1000.0};
    }

    @Test
    void shouldCreateHttpTriggerRequestPayloadSummaryWithExpectedTags() {
        DistributionSummary summary = metricsStore.processHttpPayloadSize(
            true,
            CHAIN_ID,
            CHAIN_NAME,
            ELEMENT_ID,
            ELEMENT_NAME,
            ChainElementType.HTTP_TRIGGER.getText()
        );

        summary.record(42.0);

        DistributionSummary registeredSummary = meterRegistry.find(
                "qip.engine.http.trigger.request.payload.size"
            )
            .tag(MetricsStore.CHAIN_ID_TAG, CHAIN_ID)
            .tag(MetricsStore.CHAIN_NAME_TAG, CHAIN_NAME)
            .tag(MetricsStore.ELEMENT_ID_TAG, ELEMENT_ID)
            .tag(MetricsStore.ELEMENT_NAME_TAG, ELEMENT_NAME)
            .tag(MetricsStore.ELEMENT_TYPE_TAG, ChainElementType.HTTP_TRIGGER.getText())
            .tag(MetricsStore.ENGINE_DOMAIN_TAG, ENGINE_DOMAIN)
            .summary();

        assertSame(summary, registeredSummary);
        assertEquals("bytes", registeredSummary.getId().getBaseUnit());
        assertEquals(1, registeredSummary.count());
        assertEquals(42.0, registeredSummary.totalAmount());
    }

    @Test
    void shouldReuseHttpPayloadSummaryForSameElementAndPayloadType() {
        DistributionSummary firstSummary = metricsStore.processHttpPayloadSize(
            false,
            CHAIN_ID,
            CHAIN_NAME,
            ELEMENT_ID,
            ELEMENT_NAME,
            ChainElementType.SERVICE_CALL.getText()
        );
        DistributionSummary secondSummary = metricsStore.processHttpPayloadSize(
            false,
            CHAIN_ID,
            CHAIN_NAME,
            ELEMENT_ID,
            ELEMENT_NAME,
            ChainElementType.SERVICE_CALL.getText()
        );

        assertSame(firstSummary, secondSummary);
        assertNotNull(meterRegistry.find("qip.engine.http.senders.response.payload.size").summary());
    }

    @Test
    void shouldRegisterAndRemoveChainDeploymentGaugeWhenMetricsAreEnabled() {
        metricsStore.metricsEnabled = true;

        metricsStore.processChainsDeployments(
            DEPLOYMENT_ID,
            CHAIN_ID,
            CHAIN_NAME,
            "DEPLOYED",
            "OK",
            "Snapshot name"
        );

        Gauge gauge = chainDeploymentGauge();
        assertNotNull(gauge);
        assertEquals(1.0, gauge.value());

        metricsStore.removeChainsDeployments(DEPLOYMENT_ID);

        assertNull(chainDeploymentGauge());
    }

    @Test
    void shouldSkipChainDeploymentGaugeWhenMetricsAreDisabled() {
        metricsStore.processChainsDeployments(
            DEPLOYMENT_ID,
            CHAIN_ID,
            CHAIN_NAME,
            "DEPLOYED",
            "OK",
            "Snapshot name"
        );

        assertTrue(meterRegistry.getMeters().isEmpty());
    }

    @Test
    void shouldUpdateSessionSizeGaugeAndResetMissingChains() {
        metricsStore.metricsEnabled = true;

        metricsStore.processChainSessionsSize(List.of(
            chainDataAllocationSize("chain-1", "Chain one", 100L),
            chainDataAllocationSize("chain-2", "Chain two", 250L)
        ));
        metricsStore.processChainSessionsSize(List.of(
            chainDataAllocationSize("chain-2", "Chain two", 300L)
        ));

        assertEquals(0.0, chainSessionSizeGauge("chain-1", "Chain one").value());
        assertEquals(300.0, chainSessionSizeGauge("chain-2", "Chain two").value());
    }

    @Test
    void shouldRegisterCheckpointSizeGaugeWhenMetricsAreEnabled() {
        metricsStore.metricsEnabled = true;

        metricsStore.processChainCheckpointsSize(List.of(
            chainDataAllocationSize("chain-1", "Chain one", 64L)
        ));

        assertEquals(64.0, chainCheckpointSizeGauge().value());
    }

    @Test
    void shouldSkipSizeGaugesWhenMetricsAreDisabled() {
        metricsStore.processChainSessionsSize(List.of(
            chainDataAllocationSize("chain-1", "Chain one", 100L)
        ));
        metricsStore.processChainCheckpointsSize(List.of(
            chainDataAllocationSize("chain-1", "Chain one", 64L)
        ));

        assertTrue(meterRegistry.getMeters().isEmpty());
    }

    @Test
    void shouldSkipCounterAndTimerMetersWhenMetricsAreDisabled() {
        metricsStore.processSessionFinish(CHAIN_ID, CHAIN_NAME, "COMPLETED", 100L);
        metricsStore.processChainFailure(CHAIN_ID, CHAIN_NAME, ErrorCode.SERVICE_RETURNED_ERROR);
        metricsStore.processHttpResponseCode(CHAIN_ID, CHAIN_NAME, "200");
        metricsStore.processCircuitBreakerExecution(CHAIN_ID, CHAIN_NAME, ELEMENT_ID, ELEMENT_NAME);
        metricsStore.processCircuitBreakerExecutionFallback(CHAIN_ID, CHAIN_NAME, ELEMENT_ID, ELEMENT_NAME);

        assertTrue(meterRegistry.getMeters().isEmpty());
    }

    private Gauge chainDeploymentGauge() {
        return meterRegistry.find("qip.engine.chains.deployments")
            .tag(MetricsStore.CHAIN_ID_TAG, CHAIN_ID)
            .tag(MetricsStore.CHAIN_NAME_TAG, CHAIN_NAME)
            .tag("execution_status", "DEPLOYED")
            .tag("chain_status_code", "OK")
            .tag("snapshot_name", "Snapshot name")
            .tag(MetricsStore.ENGINE_DOMAIN_TAG, ENGINE_DOMAIN)
            .gauge();
    }

    private Gauge chainSessionSizeGauge(String chainId, String chainName) {
        return dataAllocationSizeGauge("qip.engine.chain.session.size", chainId, chainName);
    }

    private Gauge chainCheckpointSizeGauge() {
        return dataAllocationSizeGauge("qip.engine.chain.checkpoint.size", "chain-1", "Chain one");
    }

    private Gauge dataAllocationSizeGauge(String metricName, String chainId, String chainName) {
        return meterRegistry.find(metricName)
            .tag(MetricsStore.CHAIN_ID_TAG, chainId)
            .tag(MetricsStore.CHAIN_NAME_TAG, chainName)
            .gauge();
    }

    private ChainDataAllocationSize chainDataAllocationSize(String chainId, String chainName, long allocatedSize) {
        return ChainDataAllocationSize.builder()
            .chainId(chainId)
            .chainName(chainName)
            .allocatedSize(allocatedSize)
            .build();
    }
}
