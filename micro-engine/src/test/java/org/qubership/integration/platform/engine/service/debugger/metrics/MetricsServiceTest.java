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

import io.micrometer.core.instrument.DistributionSummary;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.metadata.ChainInfo;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.ElementInfo;
import org.qubership.integration.platform.engine.metadata.ServiceCallInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.constants.CamelNames;
import org.qubership.integration.platform.engine.model.engine.DeploymentStatus;
import org.qubership.integration.platform.engine.model.engine.EngineDeployment;
import org.qubership.integration.platform.engine.service.debugger.ChainExecutionContext;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MetricsServiceTest {

    private static final String CHAIN_ID = "chain-id";
    private static final String CHAIN_NAME = "Chain name";
    private static final String ELEMENT_ID = "element-id";
    private static final String ELEMENT_NAME = "Element name";
    private static final String PARENT_ID = "parent-id";
    private static final String PARENT_NAME = "Parent name";
    private static final String SNAPSHOT_NAME = "Snapshot name";

    private MetricsService metricsService;

    @Mock
    MetricsStore metricsStore;

    @Mock
    DistributionSummary distributionSummary;

    @BeforeEach
    void setUp() {
        metricsService = new MetricsService(metricsStore);
    }

    @Test
    void shouldSkipStartMetricsWhenMetricsAreDisabled() {
        metricsService.processElementStartMetrics(null, null);

        verify(metricsStore).isMetricsEnabled();
        verifyNoMoreInteractions(metricsStore);
    }

    @Test
    void shouldRecordHttpSenderRequestPayloadSizeWhenStartMetricsAreEnabled() {
        Exchange exchange = exchangeWithContentLength("128");
        ChainExecutionContext context = chainExecutionContext(ChainElementType.HTTP_SENDER);
        stubPayloadMetricsEnabled();
        when(metricsStore.processHttpPayloadSize(
                true,
                CHAIN_ID,
                CHAIN_NAME,
                ELEMENT_ID,
                ELEMENT_NAME,
                ChainElementType.HTTP_SENDER.getText()
        )).thenReturn(distributionSummary);

        metricsService.processElementStartMetrics(exchange, context);

        verify(distributionSummary).record(128.0);
    }

    @Test
    void shouldRecordZeroPayloadSizeWhenContentLengthIsInvalid() {
        Exchange exchange = exchangeWithContentLength("invalid");
        ChainExecutionContext context = chainExecutionContext(ChainElementType.HTTP_TRIGGER);
        stubPayloadMetricsEnabled();
        when(metricsStore.processHttpPayloadSize(
                true,
                CHAIN_ID,
                CHAIN_NAME,
                ELEMENT_ID,
                ELEMENT_NAME,
                ChainElementType.HTTP_TRIGGER.getText()
        )).thenReturn(distributionSummary);

        metricsService.processElementStartMetrics(exchange, context);

        verify(distributionSummary).record(0.0);
    }

    @Test
    void shouldRecordServiceCallRequestPayloadSizeWhenProtocolIsHttp() {
        Exchange exchange = exchangeWithContentLength(256);
        ChainExecutionContext context = chainExecutionContext(ChainElementType.SERVICE_CALL);
        ServiceCallInfo serviceCallInfo = ServiceCallInfo.builder()
                .protocol(ChainProperties.OPERATION_PROTOCOL_TYPE_HTTP)
                .build();
        stubPayloadMetricsEnabled();
        when(metricsStore.processHttpPayloadSize(
                true,
                CHAIN_ID,
                CHAIN_NAME,
                ELEMENT_ID,
                ELEMENT_NAME,
                ChainElementType.SERVICE_CALL.getText()
        )).thenReturn(distributionSummary);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBeanForElement(exchange, ELEMENT_ID, ServiceCallInfo.class))
                    .thenReturn(serviceCallInfo);

            metricsService.processElementStartMetrics(exchange, context);
        }

        verify(distributionSummary).record(256.0);
    }

    @Test
    void shouldSkipServiceCallRequestPayloadSizeWhenProtocolIsNotHttp() {
        Exchange exchange = exchangeWithContentLength(256);
        ChainExecutionContext context = chainExecutionContext(ChainElementType.SERVICE_CALL);
        ServiceCallInfo serviceCallInfo = ServiceCallInfo.builder()
                .protocol(ChainProperties.OPERATION_PROTOCOL_TYPE_KAFKA)
                .build();
        when(metricsStore.isMetricsEnabled()).thenReturn(true);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBeanForElement(exchange, ELEMENT_ID, ServiceCallInfo.class))
                    .thenReturn(serviceCallInfo);

            metricsService.processElementStartMetrics(exchange, context);
        }

        verify(metricsStore).isMetricsEnabled();
        verifyNoMoreInteractions(metricsStore);
    }

    @Test
    void shouldRecordHttpSenderResponsePayloadSizeWhenFinishMetricsAreEnabled() {
        Exchange exchange = exchangeWithContentLength(64);
        ChainExecutionContext context = chainExecutionContext(ChainElementType.HTTP_SENDER);
        stubPayloadMetricsEnabled();
        when(metricsStore.processHttpPayloadSize(
                false,
                CHAIN_ID,
                CHAIN_NAME,
                ELEMENT_ID,
                ELEMENT_NAME,
                ChainElementType.HTTP_SENDER.getText()
        )).thenReturn(distributionSummary);

        metricsService.processElementFinishMetrics(exchange, context, false);

        verify(distributionSummary).record(64.0);
    }

    @Test
    void shouldRecordFallbackForMainCircuitBreakerWhenMainBranchFailsWithoutFallback() {
        Exchange exchange = MockExchanges.defaultExchange();
        exchange.setProperty(Properties.CIRCUIT_BREAKER_HAS_FALLBACK, false);
        ChainExecutionContext context = chainExecutionContext(
                ChainElementType.CIRCUIT_BREAKER_MAIN_ELEMENT,
            PARENT_ID,
                CamelNames.MAIN_BRANCH_CB_STEP_PREFIX
        );
        ElementInfo parentElementInfo = elementInfo(ChainElementType.CIRCUIT_BREAKER, PARENT_ID, PARENT_NAME, null);
        when(metricsStore.isMetricsEnabled()).thenReturn(true);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBeanForElement(exchange, PARENT_ID, ElementInfo.class))
                    .thenReturn(parentElementInfo);

            metricsService.processElementFinishMetrics(exchange, context, true);
        }

        verify(metricsStore).processCircuitBreakerExecutionFallback(CHAIN_ID, CHAIN_NAME, PARENT_ID, PARENT_NAME);
    }

    @Test
    void shouldRecordFallbackForCircuitBreakerFallbackBranch() {
        Exchange exchange = MockExchanges.defaultExchange();
        ChainExecutionContext context = chainExecutionContext(
                ChainElementType.CIRCUIT_BREAKER_FALLBACK,
            PARENT_ID,
                "Fallback"
        );
        ElementInfo parentElementInfo = elementInfo(ChainElementType.CIRCUIT_BREAKER, PARENT_ID, PARENT_NAME, null);
        when(metricsStore.isMetricsEnabled()).thenReturn(true);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBeanForElement(exchange, PARENT_ID, ElementInfo.class))
                    .thenReturn(parentElementInfo);

            metricsService.processElementFinishMetrics(exchange, context, false);
        }

        verify(metricsStore).processCircuitBreakerExecutionFallback(CHAIN_ID, CHAIN_NAME, PARENT_ID, PARENT_NAME);
    }

    @Test
    void shouldRecordHttpTriggerResponsePayloadSize() {
        Exchange exchange = exchangeWithContentLength(512);
        exchange.setProperty(Properties.HTTP_TRIGGER_STEP_ID, ELEMENT_ID);
        ElementInfo triggerElementInfo = elementInfo(ChainElementType.HTTP_TRIGGER, ELEMENT_ID, ELEMENT_NAME, null);
        stubPayloadMetricsEnabled();
        when(metricsStore.processHttpPayloadSize(
                false,
                CHAIN_ID,
                CHAIN_NAME,
                ELEMENT_ID,
                ELEMENT_NAME,
                ChainElementType.HTTP_TRIGGER.getText()
        )).thenReturn(distributionSummary);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class))
                    .thenReturn(deploymentInfo());
            metadataUtil.when(() -> MetadataUtil.getBeanForElement(exchange, ELEMENT_ID, ElementInfo.class))
                    .thenReturn(triggerElementInfo);

            metricsService.processHttpTriggerPayloadSize(exchange);
        }

        verify(distributionSummary).record(512.0);
    }

    @Test
    void shouldUseEmptyStatusCodeWhenDeploymentStatusCodeIsMissing() {
        org.qubership.integration.platform.engine.model.engine.DeploymentInfo deploymentInfo =
                org.qubership.integration.platform.engine.model.engine.DeploymentInfo.builder()
                        .deploymentId("deployment-id")
                        .chainId(CHAIN_ID)
                        .chainName(CHAIN_NAME)
                        .snapshotName(SNAPSHOT_NAME)
                        .build();
        EngineDeployment deployment = EngineDeployment.builder()
                .deploymentInfo(deploymentInfo)
                .status(DeploymentStatus.DEPLOYED)
                .build();

        metricsService.processChainsDeployments(deployment);

        verify(metricsStore).processChainsDeployments(
                "deployment-id",
                CHAIN_ID,
                CHAIN_NAME,
                DeploymentStatus.DEPLOYED.name(),
                "",
                SNAPSHOT_NAME
        );
    }

    private void stubPayloadMetricsEnabled() {
        when(metricsStore.isMetricsEnabled()).thenReturn(true);
        when(metricsStore.isHttpPayloadMetricsEnabled()).thenReturn(true);
    }

    private Exchange exchangeWithContentLength(Object contentLength) {
        Exchange exchange = MockExchanges.defaultExchange();
        exchange.getMessage().setHeader(HttpHeaders.CONTENT_LENGTH, contentLength);
        return exchange;
    }

    private ChainExecutionContext chainExecutionContext(ChainElementType elementType) {
        return chainExecutionContext(elementType, null, "Step");
    }

    private ChainExecutionContext chainExecutionContext(
            ChainElementType elementType,
            String parentId,
            String stepName
    ) {
        return ChainExecutionContext.builder()
                .deploymentInfo(deploymentInfo())
                .elementInfo(elementInfo(elementType, MetricsServiceTest.ELEMENT_ID, MetricsServiceTest.ELEMENT_NAME, parentId))
                .stepId(stepName)
                .stepName(stepName)
                .build();
    }

    private DeploymentInfo deploymentInfo() {
        return DeploymentInfo.builder()
                .chain(ChainInfo.builder()
                        .id(CHAIN_ID)
                        .name(CHAIN_NAME)
                        .build())
                .build();
    }

    private ElementInfo elementInfo(
            ChainElementType elementType,
            String elementId,
            String elementName,
            String parentId
    ) {
        return ElementInfo.builder()
                .id(elementId)
                .name(elementName)
                .type(elementType.getText())
                .parentId(parentId)
                .build();
    }
}
