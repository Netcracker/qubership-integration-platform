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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.metadata.ChainInfo;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.logging.SessionsLoggingLevel;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.SdsService;
import org.qubership.integration.platform.engine.service.debugger.CamelDebugger;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;
import org.qubership.integration.platform.engine.service.debugger.kafkareporting.SessionsKafkaReportingService;
import org.qubership.integration.platform.engine.service.debugger.logging.ChainLogger;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsService;
import org.qubership.integration.platform.engine.service.debugger.sessions.SessionsService;
import org.qubership.integration.platform.engine.service.debugger.util.DebuggerUtils;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.qubership.integration.platform.engine.util.ExchangeUtil;
import org.qubership.integration.platform.engine.util.InjectUtil;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
@Slf4j
@Named("chainFinishProcessor")
public class ChainFinishProcessor implements Processor {

    private final MetricsService metricsService;
    private final ChainRuntimePropertiesService propertiesService;
    private final Optional<SessionsService> sessionsService;
    private final Optional<SessionsKafkaReportingService> sessionsKafkaReportingService;
    private final Optional<SdsService> sdsService;
    private final ChainLogger chainLogger;
    private final PayloadExtractor payloadExtractor;
    private final ConcurrentHashMap<String, Long> syncDurationMap = new ConcurrentHashMap<>();

    @Inject
    public ChainFinishProcessor(MetricsService metricsService,
                                ChainRuntimePropertiesService propertiesService,
                                Instance<SessionsService> sessionsService,
                                Instance<SessionsKafkaReportingService> sessionsKafkaReportingService,
                                Instance<SdsService> sdsService,
                                ChainLogger chainLogger, PayloadExtractor payloadExtractor) {
        this.metricsService = metricsService;
        this.propertiesService = propertiesService;
        this.sessionsService = InjectUtil.injectOptional(sessionsService);
        this.sessionsKafkaReportingService = InjectUtil.injectOptional(sessionsKafkaReportingService);
        this.sdsService = InjectUtil.injectOptional(sdsService);
        this.chainLogger = chainLogger;
        this.payloadExtractor = payloadExtractor;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        ChainInfo chainInfo = MetadataUtil.getBean(exchange, DeploymentInfo.class).getChain();

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

        String sessionId = ExchangeUtil.getSessionId(exchange);
        Boolean isMainExchange = exchange.getProperty(Properties.IS_MAIN_EXCHANGE, false, Boolean.class);

        if (isMainExchange) {
            String started = exchange.getProperty(CamelConstants.Properties.START_TIME, String.class);

            long syncDuration = Duration.between(LocalDateTime.parse(started), LocalDateTime.now()).toMillis();

            syncDurationMap.merge(sessionId, syncDuration, Long::sum);
        }

        // finish session if this is the last thread
        if (sessionActiveThreadCounter == null || sessionActiveThreadCounter.decrementAndGet() <= 0) {
            CamelDebugger camelDebugger = ((CamelDebugger) exchange.getContext().getDebugger());
            ChainRuntimeProperties runtimeProperties = propertiesService.getRuntimeProperties(exchange);

            ExecutionStatus executionStatus = ExecutionStatus.COMPLETED_NORMALLY;
            for (Entry<Long, ExecutionStatus> entry : threadsStatuses.entrySet()) {
                executionStatus = ExecutionStatus.computeHigherPriorityStatus(entry.getValue(), executionStatus);
            }

            String started = exchange.getProperty(CamelConstants.Properties.START_TIME,
                String.class);
            String finished = LocalDateTime.now().toString();
            SessionsLoggingLevel sessionLevel = runtimeProperties.calculateSessionLevel(exchange);
            long duration = Duration.between(LocalDateTime.parse(started),
                LocalDateTime.parse(finished)).toMillis();

            if (ExecutionStatus.COMPLETED_WITH_ERRORS.equals(executionStatus) && (
                sessionLevel == SessionsLoggingLevel.ERROR
                    || sessionLevel == SessionsLoggingLevel.INFO)) {
                sessionsService.ifPresent(svc -> {
                    String sessionElementId = svc.moveFromSingleElCacheToCommonCache(sessionId);
                    if (StringUtils.isNotEmpty(sessionElementId)) {
                        svc.logSessionElementAfter(
                                exchange,
                                exchange.getProperty(Properties.LAST_EXCEPTION, Exception.class),
                                sessionId, sessionElementId);
                    }
                });
            }

            camelDebugger.finishCheckpointSession(exchange, sessionId, executionStatus, duration);

            ExecutionStatus finalExecutionStatus = executionStatus;
            sessionsService.ifPresent(svc -> {
                svc.finishSession(exchange, finalExecutionStatus, finished, duration,
                        syncDurationMap.getOrDefault(sessionId, 0L));
            });

            syncDurationMap.remove(sessionId);

            if (runtimeProperties.getLogLoggingLevel().isInfoLevel()) {
                chainLogger.logExchangeFinished(exchange, executionStatus, duration);
            }

            if (runtimeProperties.isDptEventsEnabled() && sessionsKafkaReportingService.isPresent()) {
                try {
                    String parentSessionId = exchange.getProperty(
                        CamelConstants.Properties.CHECKPOINT_INTERNAL_PARENT_SESSION_ID,
                        String.class);
                    String originalSessionId = exchange.getProperty(
                        CamelConstants.Properties.CHECKPOINT_INTERNAL_ORIGINAL_SESSION_ID,
                        String.class);
                    sessionsKafkaReportingService.get().sendFinishedEvent(exchange, sessionId,
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
                            chainInfo,
                            exchange.getProperty(Properties.LAST_EXCEPTION_ERROR_CODE, ErrorCode.UNEXPECTED_BUSINESS_ERROR, ErrorCode.class)
                    );
                } catch (Exception e) {
                    log.warn("Failed to create chains failures metric data", e);
                }
            }

            try {
                metricsService.processSessionFinish(chainInfo, executionStatus.toString(),
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
