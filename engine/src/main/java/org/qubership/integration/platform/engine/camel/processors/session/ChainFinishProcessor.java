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

package org.qubership.integration.platform.engine.camel.processors.session;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.engine.model.logging.LogPayload;
import org.qubership.integration.platform.engine.model.logging.SessionsLoggingLevel;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.SdsService;
import org.qubership.integration.platform.engine.service.debugger.CamelDebugger;
import org.qubership.integration.platform.engine.service.debugger.CamelDebuggerPropertiesService;
import org.qubership.integration.platform.engine.service.debugger.kafkareporting.SessionsKafkaReportingService;
import org.qubership.integration.platform.engine.service.debugger.logging.ChainLogger;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsService;
import org.qubership.integration.platform.engine.service.debugger.sessions.SessionsService;
import org.qubership.integration.platform.engine.service.debugger.util.DebuggerUtils;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class ChainFinishProcessor implements Processor {

    private final MetricsService metricsService;
    private final CamelDebuggerPropertiesService propertiesService;
    private final SessionsService sessionsService;
    private final Optional<SessionsKafkaReportingService> sessionsKafkaReportingService;
    private final Optional<SdsService> sdsService;
    private final ChainLogger chainLogger;
    private final PayloadExtractor payloadExtractor;

    @Autowired
    public ChainFinishProcessor(MetricsService metricsService,
                                CamelDebuggerPropertiesService propertiesService,
                                SessionsService sessionsService,
                                Optional<SessionsKafkaReportingService> sessionsKafkaReportingService,
                                Optional<SdsService> sdsService,
                                ChainLogger chainLogger, PayloadExtractor payloadExtractor) {
        this.metricsService = metricsService;
        this.propertiesService = propertiesService;
        this.sessionsService = sessionsService;
        this.sessionsKafkaReportingService = sessionsKafkaReportingService;
        this.sdsService = sdsService;
        this.chainLogger = chainLogger;
        this.payloadExtractor = payloadExtractor;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        AtomicInteger sessionActiveThreadCounter = exchange.getProperty(
            Properties.SESSION_ACTIVE_THREAD_COUNTER, null, AtomicInteger.class);
        if (sessionActiveThreadCounter == null) {
            log.error("Property {} is null, please re-create snapshot and redeploy related chain",
                Properties.SESSION_ACTIVE_THREAD_COUNTER);
        }

        long currentThreadId = Thread.currentThread().threadId();
        ExecutionStatus currentExchangeStatus = DebuggerUtils.extractExecutionStatus(exchange);
        Map<Long, ExecutionStatus> threadsStatuses =
            exchange.getProperty(Properties.THREAD_SESSION_STATUSES, Map.class);
        if (threadsStatuses == null) {
            log.warn("Can't find thread session statuses for current thread {}", currentThreadId);
            threadsStatuses = new HashMap<>();
        }
        threadsStatuses.put(currentThreadId, currentExchangeStatus);

        // finish session if this is the last thread
        if (sessionActiveThreadCounter == null || sessionActiveThreadCounter.decrementAndGet() <= 0) {
            CamelDebugger camelDebugger = ((CamelDebugger) exchange.getContext().getDebugger());
            CamelDebuggerProperties dbgProperties = propertiesService.getProperties(exchange,
                camelDebugger.getDeploymentId());
            String sessionId = exchange.getProperty(CamelConstants.Properties.SESSION_ID)
                .toString();

            ExecutionStatus executionStatus = ExecutionStatus.COMPLETED_NORMALLY;
            for (Entry<Long, ExecutionStatus> entry : threadsStatuses.entrySet()) {
                executionStatus = ExecutionStatus.computeHigherPriorityStatus(entry.getValue(), executionStatus);
            }

            String started = exchange.getProperty(CamelConstants.Properties.START_TIME,
                String.class);
            String finished = LocalDateTime.now().toString();
            DeploymentRuntimeProperties runtimeProperties = dbgProperties.getRuntimeProperties(exchange);
            SessionsLoggingLevel sessionLevel = runtimeProperties.calculateSessionLevel(exchange);
            long duration = Duration.between(LocalDateTime.parse(started),
                LocalDateTime.parse(finished)).toMillis();

            if (ExecutionStatus.COMPLETED_WITH_ERRORS.equals(executionStatus) && (
                sessionLevel == SessionsLoggingLevel.ERROR
                    || sessionLevel == SessionsLoggingLevel.INFO)) {
                String sessionElementId = sessionsService.moveFromSingleElCacheToCommonCache(sessionId);

                if (StringUtils.isNotEmpty(sessionElementId)) {
                    sessionsService.logSessionElementAfter(
                            exchange,
                            exchange.getProperty(Properties.LAST_EXCEPTION, Exception.class),
                            sessionId, sessionElementId,
                            dbgProperties.getMaskedFields(),
                            runtimeProperties.isMaskingEnabled());
                }
            }

            camelDebugger.finishCheckpointSession(exchange, dbgProperties, sessionId,
                executionStatus, duration);

            sessionsService.finishSession(exchange, dbgProperties, executionStatus,
                finished, duration);

            if (runtimeProperties.getLogLoggingLevel().isInfoLevel()) {

                String bodyForLogging = "<body not logged>";
                String headersForLogging = payloadExtractor.extractHeadersForLogging(exchange,
                        dbgProperties.getMaskedFields(), runtimeProperties.isMaskingEnabled()).toString();
                String exchangePropertiesForLogging = payloadExtractor.extractExchangePropertiesForLogging(
                        exchange, dbgProperties.getMaskedFields(), runtimeProperties.isMaskingEnabled()).toString();

                if (runtimeProperties.isLogPayloadEnabled()) {     //Deprecated since 24.4
                    bodyForLogging = payloadExtractor.extractBodyForLogging(exchange,
                            dbgProperties.getMaskedFields(), runtimeProperties.isMaskingEnabled());
                }

                if (runtimeProperties.getLogPayload() != null && !runtimeProperties.getLogPayload().isEmpty()) {
                    Set<LogPayload> logPayloadSettings = runtimeProperties.getLogPayload();
                    headersForLogging = logPayloadSettings.contains(LogPayload.HEADERS) ? headersForLogging : "<headers not logged>";

                    exchangePropertiesForLogging = logPayloadSettings.contains(LogPayload.PROPERTIES) ? exchangePropertiesForLogging : "<properties not logged>";

                    bodyForLogging = logPayloadSettings.contains(LogPayload.BODY) ? payloadExtractor.extractBodyForLogging(exchange,
                            dbgProperties.getMaskedFields(), dbgProperties.getRuntimeProperties(exchange)
                                    .isMaskingEnabled()) : "<body not logged>";
                }

                chainLogger.logExchangeFinished(dbgProperties, bodyForLogging, headersForLogging,
                        exchangePropertiesForLogging, executionStatus, duration);
            }

            if (runtimeProperties.isDptEventsEnabled() && sessionsKafkaReportingService.isPresent()) {
                try {
                    String parentSessionId = exchange.getProperty(
                        CamelConstants.Properties.CHECKPOINT_INTERNAL_PARENT_SESSION_ID,
                        String.class);
                    String originalSessionId = exchange.getProperty(
                        CamelConstants.Properties.CHECKPOINT_INTERNAL_ORIGINAL_SESSION_ID,
                        String.class);
                    sessionsKafkaReportingService.get().sendFinishedEvent(exchange, dbgProperties, sessionId,
                        originalSessionId, parentSessionId,
                        executionStatus);
                } catch (Exception e) {
                    log.error("Failed to send DPT events", e);
                }
            }

            if (ExecutionStatus.COMPLETED_WITH_WARNINGS.equals(executionStatus)
                    || ExecutionStatus.COMPLETED_WITH_ERRORS.equals(executionStatus)) {
                try {
                    metricsService.processChainFailure(
                            dbgProperties.getDeploymentInfo(),
                            exchange.getProperty(Properties.LAST_EXCEPTION_ERROR_CODE, ErrorCode.UNEXPECTED_BUSINESS_ERROR, ErrorCode.class)
                    );
                } catch (Exception e) {
                    log.warn("Failed to create chains failures metric data", e);
                }
            }

            try {
                metricsService.processSessionFinish(dbgProperties, executionStatus.toString(),
                    duration);
            } catch (Exception e) {
                log.warn("Failed to create metrics data", e);
            }

            String sdsExecutionId = exchange.getProperty(CamelConstants.Properties.SDS_EXECUTION_ID_PROP, String.class);
            if (sdsExecutionId != null) {
                if (sdsService.isPresent()) {
                    if (ExecutionStatus.COMPLETED_WITH_ERRORS.equals(executionStatus)) {
                        sdsService.get().setJobInstanceFailed(sdsExecutionId,
                            DebuggerUtils.getExceptionFromExchange(exchange));
                    } else {
                        sdsService.get().setJobInstanceFinished(sdsExecutionId);
                    }
                }
            }
        }
    }
}
