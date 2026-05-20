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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.metadata.ChainInfo;
import org.qubership.integration.platform.engine.metadata.ElementInfo;
import org.qubership.integration.platform.engine.metadata.ServiceCallInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.constants.CamelNames;
import org.qubership.integration.platform.engine.model.engine.DeploymentInfo;
import org.qubership.integration.platform.engine.model.engine.EngineDeployment;
import org.qubership.integration.platform.engine.service.debugger.ChainExecutionContext;

import java.util.Optional;

@Slf4j
@ApplicationScoped
public class MetricsService {

    private final MetricsStore metricsStore;

    @Inject
    public MetricsService(MetricsStore metricsStore) {
        this.metricsStore = metricsStore;
    }

    public void processElementStartMetrics(
        Exchange exchange,
        ChainExecutionContext chainExecutionContext
    ) {
        if (!metricsStore.isMetricsEnabled()) {
            return;
        }

        try {
            DistributionSummary distributionSummary;

            String chainId = chainExecutionContext.getDeploymentInfo().getChain().getId();
            String chainName = chainExecutionContext.getDeploymentInfo().getChain().getName();
            String elementId = chainExecutionContext.getElementInfo().getId();
            String elementName = chainExecutionContext.getElementInfo().getName();
            String elementType = chainExecutionContext.getElementInfo().getType();

            switch (chainExecutionContext.getElementType()) {
                case CIRCUIT_BREAKER:
                case CIRCUIT_BREAKER_2:
                    if (chainExecutionContext.getStepId().equals(chainExecutionContext.getStepName())) {
                        metricsStore.processCircuitBreakerExecution(chainId, chainName, elementId, elementName);
                    }
                    break;
                case HTTP_TRIGGER:
                case HTTP_SENDER:
                    if (metricsStore.isHttpPayloadMetricsEnabled()) {
                        distributionSummary = metricsStore.processHttpPayloadSize(
                            true, chainId, chainName, elementId, elementName, elementType);
                        distributionSummary.record(calculatePayloadSize(exchange));
                    }
                    break;
                case SERVICE_CALL:
                    if (metricNeedsToBeRecorded(exchange, elementId) && metricsStore.isHttpPayloadMetricsEnabled()) {
                        distributionSummary = metricsStore.processHttpPayloadSize(
                                true, chainId, chainName, elementId, elementName, elementType);
                        distributionSummary.record(calculatePayloadSize(exchange));
                    }
                    break;
            }
        } catch (Exception e) {
            log.warn("Failed to create metrics data", e);
        }
    }

    public void processElementFinishMetrics(
        Exchange exchange,
        ChainExecutionContext chainExecutionContext,
        boolean failed
    ) {
        if (!metricsStore.isMetricsEnabled()) {
            return;
        }

        try {
            DistributionSummary distributionSummary;

            String chainId = chainExecutionContext.getDeploymentInfo().getChain().getId();
            String chainName = chainExecutionContext.getDeploymentInfo().getChain().getName();
            String elementId = chainExecutionContext.getElementInfo().getId();
            String elementName = chainExecutionContext.getElementInfo().getName();
            String elementType = chainExecutionContext.getElementInfo().getType();

            String parentId = chainExecutionContext.getElementInfo().getParentId();
            String parentName = Optional.ofNullable(parentId)
                    .map(id -> MetadataUtil.getBeanForElement(exchange, id, ElementInfo.class))
                    .map(ElementInfo::getName)
                    .orElse("");

            ChainElementType chainElementType = chainExecutionContext.getElementType();

            switch (chainElementType) {
                case CIRCUIT_BREAKER:
                case CIRCUIT_BREAKER_2:
                case CIRCUIT_BREAKER_MAIN_ELEMENT:
                case CIRCUIT_BREAKER_MAIN_ELEMENT_2:
                    if (chainElementType.equals(ChainElementType.CIRCUIT_BREAKER_MAIN_ELEMENT)
                            || chainElementType.equals(ChainElementType.CIRCUIT_BREAKER_MAIN_ELEMENT_2)) {
                        elementId = parentId;
                        elementName = parentName;
                    }
                    boolean hasFallback = Boolean.parseBoolean(String.valueOf(
                            exchange.getProperty(Properties.CIRCUIT_BREAKER_HAS_FALLBACK)));
                    if (
                            failed
                            && !hasFallback
                            && CamelNames.MAIN_BRANCH_CB_STEP_PREFIX
                                    .equals(chainExecutionContext.getStepName())
                    ) {
                        metricsStore.processCircuitBreakerExecutionFallback(
                                chainId, chainName, elementId, elementName);
                    }
                    break;
                case CIRCUIT_BREAKER_FALLBACK:
                case CIRCUIT_BREAKER_FALLBACK_2:
                    metricsStore.processCircuitBreakerExecutionFallback(
                        chainId, chainName, parentId, parentName);
                    break;
                case HTTP_SENDER:
                    if (metricsStore.isHttpPayloadMetricsEnabled()) {
                        distributionSummary = metricsStore.processHttpPayloadSize(
                            false, chainId, chainName, elementId, elementName, elementType);
                        distributionSummary.record(calculatePayloadSize(exchange));
                    }
                    break;
                case SERVICE_CALL:
                    if (metricNeedsToBeRecorded(exchange, elementId) && metricsStore.isHttpPayloadMetricsEnabled()) {
                        distributionSummary = metricsStore.processHttpPayloadSize(
                                false, chainId, chainName, elementId, elementName, elementType);
                        distributionSummary.record(calculatePayloadSize(exchange));
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            log.warn("Failed to create metrics data", e);
        }
    }

    private boolean metricNeedsToBeRecorded(Exchange exchange, String elementId) {
        ServiceCallInfo serviceCallInfo = MetadataUtil.getBeanForElement(exchange, elementId, ServiceCallInfo.class);
        return metricNeedsToBeRecorded(serviceCallInfo);
    }

    private boolean metricNeedsToBeRecorded(ServiceCallInfo serviceCallInfo) {
        return ChainProperties.OPERATION_PROTOCOL_TYPE_HTTP.equals(serviceCallInfo.getProtocol());
    }

    private int calculatePayloadSize(Exchange exchange) {
        Object length = exchange.getMessage().getHeader(HttpHeaders.CONTENT_LENGTH);
        if (length == null) {
            return 0;
        }

        try {
            if (length instanceof Integer) {
                return (Integer) length;
            }
            return Integer.parseInt(length.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void processHttpResponseCode(
            ChainInfo chainInfo,
            String responseCode
    ) {
        metricsStore.processHttpResponseCode(chainInfo.getId(), chainInfo.getName(), responseCode);
    }

    public void processHttpTriggerPayloadSize(Exchange exchange) {
        if (metricsStore.isMetricsEnabled() && metricsStore.isHttpPayloadMetricsEnabled()) {
            org.qubership.integration.platform.engine.metadata.DeploymentInfo deploymentInfo =
                    MetadataUtil.getBean(exchange, org.qubership.integration.platform.engine.metadata.DeploymentInfo.class);
            String id = exchange.getProperty(Properties.HTTP_TRIGGER_STEP_ID).toString();
            ElementInfo elementInfo = MetadataUtil.getBeanForElement(exchange, id, ElementInfo.class);
            String elementId = elementInfo.getId();
            String elementType = elementInfo.getType();
            String elementName = elementInfo.getName();

            DistributionSummary distributionSummary = metricsStore.processHttpPayloadSize(
                    false,
                    deploymentInfo.getChain().getId(),
                    deploymentInfo.getChain().getName(),
                    elementId,
                    elementName,
                    elementType);
            distributionSummary.record(calculatePayloadSize(exchange));
        }
    }

    public void processSessionFinish(ChainInfo chainInfo, String executionStatus,
        long duration) {
        metricsStore.processSessionFinish(
            chainInfo.getId(),
            chainInfo.getName(),
            executionStatus,
            duration);
    }

    public void processChainFailure(ChainInfo chainInfo, ErrorCode errorCode) {
        metricsStore.processChainFailure(
                chainInfo.getId(),
                chainInfo.getName(),
                errorCode
        );
    }

    public void processChainsDeployments(EngineDeployment deployment) {
        DeploymentInfo deploymentInfo = deployment.getDeploymentInfo();
        String statusCode = deploymentInfo.getChainStatusCode();
        if (statusCode == null) {
            statusCode = "";
        }
        metricsStore.processChainsDeployments(
                deploymentInfo.getDeploymentId(),
                deploymentInfo.getChainId(),
                deploymentInfo.getChainName(),
                deployment.getStatus().name(),
                statusCode,
                deploymentInfo.getSnapshotName());
    }
}
