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

import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.constants.CamelNames;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineDeployment;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import io.micrometer.core.instrument.DistributionSummary;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class MetricsService {

    private final MetricsStore metricsStore;

    @Autowired
    public MetricsService(MetricsStore metricsStore) {
        this.metricsStore = metricsStore;
    }

    public void processElementStartMetrics(
        Exchange exchange,
        CamelDebuggerProperties dbgProperties,
        String stepId,
        String stepName,
        ChainElementType elementType
    ) {
        if (!metricsStore.isMetricsEnabled()) {
            return;
        }

        try {
            Map<String, String> stepProperties = dbgProperties.getElementProperty(stepId);
            DeploymentInfo deploymentInfo = dbgProperties.getDeploymentInfo();
            DistributionSummary distributionSummary;

            switch (elementType) {
                case CIRCUIT_BREAKER:
                case CIRCUIT_BREAKER_2:
                    if (stepId.equals(stepName)) {
                        metricsStore.processCircuitBreakerExecution(
                            dbgProperties.getDeploymentInfo().getChainId(),
                            dbgProperties.getDeploymentInfo().getChainName(),
                            dbgProperties.getElementProperty(stepId)
                                .get(ChainProperties.ELEMENT_ID),
                            dbgProperties.getElementProperty(stepId)
                                .get(ChainProperties.ELEMENT_NAME));
                    }
                    break;
                case HTTP_TRIGGER:
                case HTTP_SENDER:
                    distributionSummary = metricsStore.processHttpPayloadSize(
                            true,
                            deploymentInfo.getChainId(),
                            deploymentInfo.getChainName(),
                            stepProperties.get(ChainProperties.ELEMENT_ID),
                            stepProperties.get(ChainProperties.ELEMENT_NAME),
                            stepProperties.get(ChainProperties.ELEMENT_TYPE));
                    distributionSummary.record(calculatePayloadSize(exchange));
                    break;
                case SERVICE_CALL:
                    if (metricNeedsToBeRecorded(stepProperties)) {
                        distributionSummary = metricsStore.processHttpPayloadSize(
                                true,
                                deploymentInfo.getChainId(),
                                deploymentInfo.getChainName(),
                                stepProperties.get(ChainProperties.ELEMENT_ID),
                                stepProperties.get(ChainProperties.ELEMENT_NAME),
                                stepProperties.get(ChainProperties.ELEMENT_TYPE));
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
        CamelDebuggerProperties dbgProperties,
        String stepId,
        String stepName,
        ChainElementType elementType,
        boolean failed
    ) {
        if (!metricsStore.isMetricsEnabled()) {
            return;
        }

        try {
            Map<String, String> stepProperties = dbgProperties.getElementProperty(stepId);
            DeploymentInfo deploymentInfo = dbgProperties.getDeploymentInfo();
            DistributionSummary distributionSummary;

            switch (elementType) {
                case CIRCUIT_BREAKER:
                case CIRCUIT_BREAKER_2:
                case CIRCUIT_BREAKER_MAIN_ELEMENT:
                case CIRCUIT_BREAKER_MAIN_ELEMENT_2:
                    String elementId = stepProperties.get(ChainProperties.ELEMENT_ID);
                    String elementName = stepProperties.get(ChainProperties.ELEMENT_NAME);
                    if (elementType == ChainElementType.CIRCUIT_BREAKER_MAIN_ELEMENT
                        || elementType == ChainElementType.CIRCUIT_BREAKER_MAIN_ELEMENT_2) {
                        elementId = stepProperties.get(
                            ChainProperties.PARENT_ELEMENT_ORIGINAL_ID);
                        elementName = stepProperties.get(ChainProperties.PARENT_ELEMENT_NAME);
                    }
                    boolean hasFallback = Boolean.parseBoolean(String.valueOf(exchange.getProperty(
                        Properties.CIRCUIT_BREAKER_HAS_FALLBACK)));
                    if (failed && !hasFallback && CamelNames.MAIN_BRANCH_CB_STEP_PREFIX.equals(
                        stepName)) {
                        metricsStore.processCircuitBreakerExecutionFallback(
                            deploymentInfo.getChainId(),
                            deploymentInfo.getChainName(),
                            elementId,
                            elementName);
                    }
                    break;
                case CIRCUIT_BREAKER_FALLBACK:
                case CIRCUIT_BREAKER_FALLBACK_2:
                    metricsStore.processCircuitBreakerExecutionFallback(
                        deploymentInfo.getChainId(),
                        deploymentInfo.getChainName(),
                        stepProperties.get(ChainProperties.PARENT_ELEMENT_ORIGINAL_ID),
                        stepProperties.get(ChainProperties.PARENT_ELEMENT_NAME));
                    break;
                case HTTP_SENDER:
                    distributionSummary = metricsStore.processHttpPayloadSize(
                            false,
                            deploymentInfo.getChainId(),
                            deploymentInfo.getChainName(),
                            stepProperties.get(ChainProperties.ELEMENT_ID),
                            stepProperties.get(ChainProperties.ELEMENT_NAME),
                            stepProperties.get(ChainProperties.ELEMENT_TYPE));
                    distributionSummary.record(calculatePayloadSize(exchange));
                    break;
                case SERVICE_CALL:
                    if (metricNeedsToBeRecorded(stepProperties)) {
                        distributionSummary = metricsStore.processHttpPayloadSize(
                                false,
                                deploymentInfo.getChainId(),
                                deploymentInfo.getChainName(),
                                stepProperties.get(ChainProperties.ELEMENT_ID),
                                stepProperties.get(ChainProperties.ELEMENT_NAME),
                                stepProperties.get(ChainProperties.ELEMENT_TYPE));
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

    private boolean metricNeedsToBeRecorded(Map<String, String> stepProperties) {
        return ChainProperties.OPERATION_PROTOCOL_TYPE_HTTP.equals(stepProperties.get(ChainProperties.OPERATION_PROTOCOL_TYPE_PROP));
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

    public void processHttpResponseCode(CamelDebuggerProperties dbgProperties,
        String responseCode) {
        metricsStore.processHttpResponseCode(
            dbgProperties.getDeploymentInfo().getChainId(),
            dbgProperties.getDeploymentInfo().getChainName(),
            responseCode);
    }

    public void processHttpTriggerPayloadSize(Exchange exchange, CamelDebuggerProperties dbgProperties) {
        if (metricsStore.isMetricsEnabled()) {
            DeploymentInfo deploymentInfo = dbgProperties.getDeploymentInfo();

            Map<String, String> elementProperties = dbgProperties.getElementProperty(exchange.getProperty(Properties.HTTP_TRIGGER_STEP_ID).toString());
            String elementId = elementProperties.get(ChainProperties.ELEMENT_ID);
            String elementType = elementProperties.get(ChainProperties.ELEMENT_TYPE);
            String elementName = elementProperties.get(ChainProperties.ELEMENT_NAME);

            DistributionSummary distributionSummary = metricsStore.processHttpPayloadSize(
                    false,
                    deploymentInfo.getChainId(),
                    deploymentInfo.getChainName(),
                    elementId,
                    elementName,
                    elementType);
            distributionSummary.record(calculatePayloadSize(exchange));
        }
    }

    public void processSessionFinish(CamelDebuggerProperties dbgProperties, String executionStatus,
        long duration) {
        metricsStore.processSessionFinish(
            dbgProperties.getDeploymentInfo().getChainId(),
            dbgProperties.getDeploymentInfo().getChainName(),
            executionStatus,
            duration);
    }

    public void processChainFailure(DeploymentInfo deploymentInfo, ErrorCode errorCode) {
        metricsStore.processChainFailure(
                deploymentInfo.getChainId(),
                deploymentInfo.getChainName(),
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
