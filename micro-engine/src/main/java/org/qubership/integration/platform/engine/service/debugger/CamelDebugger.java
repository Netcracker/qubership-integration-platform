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

package org.qubership.integration.platform.engine.service.debugger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.NamedNode;
import org.apache.camel.Processor;
import org.apache.camel.impl.debugger.DefaultDebugger;
import org.apache.camel.model.StepDefinition;
import org.apache.camel.spi.CamelEvent.*;
import org.apache.hc.core5.http.HttpHeaders;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeContextPropagation;
import org.qubership.integration.platform.engine.errorhandling.ChainExecutionTimeoutException;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.ElementInfo;
import org.qubership.integration.platform.engine.metadata.MaskedFields;
import org.qubership.integration.platform.engine.metadata.ServiceCallInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.model.Session;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.constants.CamelNames;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;
import org.qubership.integration.platform.engine.model.logging.*;
import org.qubership.integration.platform.engine.model.sessionsreporting.EventSourceType;
import org.qubership.integration.platform.engine.persistence.shared.entity.Checkpoint;
import org.qubership.integration.platform.engine.persistence.shared.entity.SessionInfo;
import org.qubership.integration.platform.engine.service.CheckpointSessionService;
import org.qubership.integration.platform.engine.service.ExchangePropertyService;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.debugger.kafkareporting.SessionsKafkaReportingService;
import org.qubership.integration.platform.engine.service.debugger.logging.ChainLogger;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsService;
import org.qubership.integration.platform.engine.service.debugger.sessions.SessionsService;
import org.qubership.integration.platform.engine.service.debugger.tracing.TracingService;
import org.qubership.integration.platform.engine.service.debugger.util.DebuggerUtils;
import org.qubership.integration.platform.engine.service.debugger.util.MaskedFieldUtils;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.qubership.integration.platform.engine.util.CheckpointUtils;
import org.qubership.integration.platform.engine.util.ExchangeUtil;
import org.qubership.integration.platform.engine.util.IdentifierUtils;
import org.qubership.integration.platform.engine.util.InjectUtil;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.qubership.integration.platform.engine.model.ChainElementType.CHECKPOINT;

@Slf4j
@ApplicationScoped
public class CamelDebugger extends DefaultDebugger {

    private final EngineInfo engineInfo;
    private final TracingService tracingService;
    private final CheckpointSessionService checkpointSessionService;
    private final MetricsService metricsService;
    private final ChainLogger chainLogger;
    private final Optional<SessionsKafkaReportingService> sessionsKafkaReportingService;
    private final SessionsService sessionsService;
    private final PayloadExtractor payloadExtractor;
    private final VariablesService variablesService;
    private final ChainRuntimePropertiesService chainRuntimePropertiesService;
    private final CamelExchangeContextPropagation exchangeContextPropagation;
    private final ExchangePropertyService exchangePropertyService;

    @Inject
    public CamelDebugger(
            EngineInfo engineInfo,
            TracingService tracingService,
            CheckpointSessionService checkpointSessionService,
            MetricsService metricsService,
            ChainLogger chainLogger,
            Instance<SessionsKafkaReportingService> sessionsKafkaReportingService,
            SessionsService sessionsService,
            PayloadExtractor payloadExtractor,
            VariablesService variablesService,
            ChainRuntimePropertiesService propertiesService,
            CamelExchangeContextPropagation exchangeContextPropagation,
            ExchangePropertyService exchangePropertyService
    ) {
        this.engineInfo = engineInfo;
        this.tracingService = tracingService;
        this.checkpointSessionService = checkpointSessionService;
        this.metricsService = metricsService;
        this.chainLogger = chainLogger;
        this.sessionsKafkaReportingService = InjectUtil.injectOptional(sessionsKafkaReportingService);
        this.sessionsService = sessionsService;
        this.payloadExtractor = payloadExtractor;
        this.variablesService = variablesService;
        this.chainRuntimePropertiesService = propertiesService;
        this.exchangeContextPropagation = exchangeContextPropagation;
        this.exchangePropertyService = exchangePropertyService;
    }

    private ChainExecutionContext getExecutionContext(Exchange exchange, String nodeId) {
        String id = isNull(nodeId) ? "" : nodeId;
        String stepId = DebuggerUtils.getNodeIdFormatted(id);
        String stepName = DebuggerUtils.getStepNameFormatted(id);
        String elementId = DebuggerUtils.getStepChainElementId(id);
        ElementInfo elementInfo = MetadataUtil.getBeanForElement(exchange, elementId, ElementInfo.class);
        return ChainExecutionContext.builder()
                .deploymentInfo(MetadataUtil.getBean(exchange, DeploymentInfo.class))
                .chainRuntimeProperties(chainRuntimePropertiesService.getRuntimeProperties(exchange))
                .stepId(stepId)
                .stepName(stepName)
                .elementInfo(elementInfo)
                .build();
    }

    private ChainExecutionContext getExecutionContext(Exchange exchange) {
        return getExecutionContext(exchange, "");
    }

    @Override
    public boolean onEvent(Exchange exchange, ExchangeEvent event) {
        switch (event) {
            case ExchangeCreatedEvent ev -> exchangeCreated(exchange);
            case StepStartedEvent ev -> stepStarted(exchange, ev);
            case StepCompletedEvent ev -> stepFinished(exchange, ev, false);
            case StepFailedEvent ev -> stepFinished(exchange, ev, true);
            case ExchangeCompletedEvent ev -> exchangeFinished(exchange);
            case ExchangeFailedEvent ev -> exchangeFinished(exchange);
            default -> {
            }
        }

        return super.onEvent(exchange, event);
    }

    @Override
    @SuppressWarnings("checkstyle:FallThrough")
    public boolean beforeProcess(Exchange exchange, Processor processor, NamedNode definition) {
        ChainRuntimeProperties chainRuntimeProperties = chainRuntimePropertiesService.getRuntimeProperties(exchange);

        initOrActivatePropagatedContext(exchange);

        SessionsLoggingLevel sessionLevel = chainRuntimeProperties.calculateSessionLevel(exchange);
        LogLoggingLevel logLoggingLevel = chainRuntimeProperties.getLogLoggingLevel();

        String sessionId = ExchangeUtil.getSessionId(exchange);
        String nodeId = definition.getId();
        boolean sessionShouldBeLogged = exchange.getProperty(
                CamelConstants.Properties.SESSION_SHOULD_BE_LOGGED,
                Boolean.class);

        setLoggerContext(exchange, nodeId);

        if (exchange.getProperty(CamelConstants.Properties.ELEMENT_EXECUTION_MAP) == null) {
            exchange.setProperty(CamelConstants.Properties.ELEMENT_EXECUTION_MAP, new ConcurrentHashMap<>());
        }

        if (CamelConstants.CUSTOM_STEP_ID_PATTERN.matcher(nodeId).matches()) {
            String stepName = DebuggerUtils.getStepNameFormatted(nodeId);
            String elementId = DebuggerUtils.getStepChainElementId(nodeId);
            ElementInfo elementInfo = MetadataUtil.getBeanForElement(exchange, elementId, ElementInfo.class);
            ChainElementType elementType = ChainElementType.fromString(elementInfo.getType());
            logBeforeStepStarted(exchange, stepName, elementId, elementType);
            handleElementBeforeProcess(exchange, elementId, elementType);
        }

        if (IdentifierUtils.isValidUUID(nodeId)) {
            ElementInfo elementInfo = MetadataUtil.getBeanForElement(exchange, nodeId, ElementInfo.class);
            if (tracingService.isTracingEnabled()) {
                tracingService.addElementTracingTags(exchange, elementInfo);
            }

            ChainElementType chainElementType = ChainElementType.fromString(elementInfo.getType());

            boolean isElementForSessionsLevel = ChainElementType.isElementForInfoSessionsLevel(
                    chainElementType);

            Payload payload = payloadExtractor.extractPayload(exchange);

            if (!(definition instanceof StepDefinition)) { // not a step

                String sessionElementId = UUID.randomUUID().toString();
                switch (sessionLevel) {
                    case ERROR:
                        sessionsService.putElementToSingleElCache(
                                exchange,
                                sessionId,
                                sessionElementId,
                                nodeId,
                                payload
                        );
                        break;
                    case INFO:
                        sessionsService.putElementToSingleElCache(
                                exchange,
                                sessionId,
                                sessionElementId,
                                nodeId,
                                payload
                        );
                        if (!isElementForSessionsLevel) {
                            break;
                        }
                    case DEBUG:
                        if (sessionShouldBeLogged) {
                            sessionsService.logSessionElementBefore(
                                    exchange,
                                    sessionId,
                                    sessionElementId,
                                    nodeId,
                                    payload
                            );
                        }
                        break;
                    default:
                        break;
                }
            }

            try {
                chainLogger.logBeforeProcess(exchange, chainRuntimeProperties, nodeId, payload);
            } catch (Exception e) {
                log.warn("Failed to log before process", e);
            }
        }

        return super.beforeProcess(exchange, processor, definition);
    }

    @Override
    @SuppressWarnings("checkstyle:FallThrough")
    public boolean afterProcess(
            Exchange exchange,
            Processor processor,
            NamedNode definition,
            long timeTaken
    ) {
        ChainRuntimeProperties chainRuntimeProperties = chainRuntimePropertiesService.getRuntimeProperties(exchange);

        checkExecutionTimeout(exchange);

        initOrActivatePropagatedContext(exchange);

        SessionsLoggingLevel actualSessionLevel = chainRuntimeProperties.calculateSessionLevel(exchange);
        LogLoggingLevel logLoggingLevel = chainRuntimeProperties.getLogLoggingLevel();

        String nodeId = definition.getId();

        setLoggerContext(exchange, nodeId);

        boolean sessionShouldBeLogged = exchange.getProperty(
                CamelConstants.Properties.SESSION_SHOULD_BE_LOGGED,
                Boolean.class);

        if (IdentifierUtils.isValidUUID(nodeId)) {
            ElementInfo elementInfo = MetadataUtil.getBeanForElement(exchange, nodeId, ElementInfo.class);
            ChainElementType chainElementType = ChainElementType.fromString(elementInfo.getType());

            boolean isElementForSessionsLevel = ChainElementType.isElementForInfoSessionsLevel(
                    chainElementType);

            setFailedElementId(exchange, elementInfo);
            Payload payload = payloadExtractor.extractPayload(exchange);
            switch (actualSessionLevel) {
                case INFO:
                    if (!isElementForSessionsLevel) {
                        break;
                    }
                case DEBUG:
                    if (sessionShouldBeLogged) {
                        String sessionId = ExchangeUtil.getSessionId(exchange);
                        String splitIdChain = (String) exchange.getProperty(
                                CamelConstants.Properties.SPLIT_ID_CHAIN);
                        String sessionElementId = ((Map<String, String>) exchange.getProperty(
                                CamelConstants.Properties.ELEMENT_EXECUTION_MAP)).get(DebuggerUtils.getNodeIdForExecutionMap(nodeId, splitIdChain));
                        if (sessionElementId == null) {
                            sessionElementId = ((Map<String, String>) exchange.getProperty(
                                    CamelConstants.Properties.ELEMENT_EXECUTION_MAP)).get(nodeId);
                        }
                        sessionsService.logSessionElementAfter(
                                exchange,
                                null,
                                sessionId,
                                sessionElementId,
                                payload);
                    }
                    break;
                default:
                    break;
            }

            try {
                chainLogger.logAfterProcess(exchange, chainRuntimeProperties, payload, nodeId, timeTaken);
            } catch (Exception e) {
                log.warn("Failed to log after process", e);
            }
        }

        return super.afterProcess(exchange, processor, definition, timeTaken);
    }

    private void exchangeCreated(Exchange exchange) {
        initOrActivatePropagatedContext(exchange);
        ExchangeUtil.initInternalProperties(exchange);
        variablesService.injectVariablesToExchangeProperties(exchange.getProperties());

        String sessionId = Optional.ofNullable(ExchangeUtil.getSessionId(exchange))
                .orElseGet(() -> startNewSession(exchange).getId());
        ExchangeUtil.putToExchangeMap(sessionId, exchange);

        // Propagate masked fields if not already present
        Set<String> maskedFields = MetadataUtil.getBean(exchange, MaskedFields.class);
        MaskedFieldUtils.addMaskedFields(exchange, maskedFields);

        if (tracingService.isTracingEnabled()) {
            // tracing context doesn't present at this point,
            // only store custom tags to camel property
            tracingService.addChainTracingTags(exchange);
        }
    }

    private Session startNewSession(Exchange exchange) {
        String parentSessionId = Optional.ofNullable(CheckpointUtils.extractTriggeredCheckpointInfo(exchange))
                .map(checkpointInfo -> checkpointSessionService.findCheckpoint(
                        checkpointInfo.sessionId(),
                        checkpointInfo.chainId(),
                        checkpointInfo.checkpointElementId()))
                .map(Checkpoint::getSession)
                .map(SessionInfo::getId)
                .orElse(null);
        Session session = sessionsService.startSession(exchange, parentSessionId);
        ExchangeUtil.setSessionProperties(exchange, session, sessionsService.sessionShouldBeLogged());

        if (chainHasCheckpointElements(exchange)) {
            checkpointSessionService.saveSession(new SessionInfo(session));
        }

        String originalSessionId = checkpointSessionService.findOriginalSessionInfo(parentSessionId)
                .map(SessionInfo::getId)
                .orElse(parentSessionId);
        CheckpointUtils.setSessionProperties(exchange, parentSessionId, originalSessionId);
        if (chainRuntimePropertiesService.getRuntimeProperties(exchange).isDptEventsEnabled()) {
            sessionsKafkaReportingService.ifPresent(svc -> svc.addToQueue(
                    exchange, session.getId(), originalSessionId, parentSessionId,
                    EventSourceType.SESSION_STARTED));
        }
        exchange.setProperty(CamelConstants.Properties.THREAD_SESSION_STATUSES, new HashMap<Long, ExecutionStatus>());
        return session;
    }

    private boolean chainHasCheckpointElements(Exchange exchange) {
        return MetadataUtil.getElementsInfo(exchange)
                .anyMatch(elementInfo -> CHECKPOINT.getText().equals(elementInfo.getType()));
    }

    private void exchangeFinished(Exchange exchange) {
        String sessionId = ExchangeUtil.getSessionId(exchange);
        ExchangeUtil.removeFromExchangeMap(sessionId, exchange);
        log.debug("Exchange finished in thread '{}'", Thread.currentThread().getName());
    }

    @SuppressWarnings("checkstyle:FallThrough")
    private void stepStarted(Exchange exchange, StepEvent event) {
        ChainExecutionContext executionContext = getExecutionContext(exchange, event.getStepId());
        String sessionId = ExchangeUtil.getSessionId(exchange);

        String sessionElementId = UUID.randomUUID().toString();
        boolean sessionShouldBeLogged = exchange.getProperty(
                CamelConstants.Properties.SESSION_SHOULD_BE_LOGGED,
                Boolean.class
        );

        metricsService.processElementStartMetrics(exchange, executionContext);

        switch (executionContext.getChainRuntimeProperties().calculateSessionLevel(exchange)) {
            case ERROR:
                sessionsService.putStepElementToSingleElCache(exchange, sessionId,
                        sessionElementId, executionContext.getStepName(), executionContext.getElementInfo());
                break;
            case INFO:
                if (!ChainElementType.isElementForInfoSessionsLevel(executionContext.getElementType())) {
                    break;
                }
            case DEBUG:
                if (sessionShouldBeLogged) {
                    sessionsService.logSessionStepElementBefore(exchange, sessionId,
                            sessionElementId, executionContext.getStepName(), executionContext.getElementInfo());

                    String executionStepId = executionContext.getStepName();
                    if (ChainElementType.isWrappedInStepElement(executionContext.getElementType())) {
                        executionStepId = DebuggerUtils.getNodeIdForExecutionMap(
                                executionStepId,
                                (String) exchange.getProperty(
                                        CamelConstants.Properties.SPLIT_ID_CHAIN)
                        );
                    }
                    ((Map<String, String>) exchange.getProperty(
                            CamelConstants.Properties.ELEMENT_EXECUTION_MAP)).put(executionStepId, sessionElementId);
                }
                break;
            default:
                break;
        }

        exchange.getProperty(CamelConstants.Properties.STEPS, Deque.class).push(sessionElementId);
    }

    @SuppressWarnings("checkstyle:FallThrough")
    private void stepFinished(Exchange exchange, StepEvent event, boolean failed) {
        ChainExecutionContext executionContext = getExecutionContext(exchange, event.getStepId());
        String sessionId = ExchangeUtil.getSessionId(exchange);

        boolean sessionShouldBeLogged = exchange.getProperty(
                CamelConstants.Properties.SESSION_SHOULD_BE_LOGGED,
                Boolean.class
        );

        metricsService.processElementFinishMetrics(exchange, executionContext, failed);

        setFailedElementId(exchange, executionContext.getElementInfo());
        setLoggerContext(exchange, executionContext.getStepId());
        logAfterStepFinished(exchange, executionContext);

        switch (executionContext.getChainRuntimeProperties().calculateSessionLevel(exchange)) {
            case INFO:
                if (!ChainElementType.isElementForInfoSessionsLevel(executionContext.getElementType())) {
                    break;
                }
            case DEBUG:
                if (sessionShouldBeLogged) {
                    String sessionElementId = ((Deque<String>) exchange.getProperty(
                            CamelConstants.Properties.STEPS)).pop();
                    if (failed) {
                        DebuggerUtils.removeStepPropertyFromAllExchanges(exchange,
                                sessionElementId);
                    }
                    sessionsService.logSessionElementAfter(exchange, null, sessionId, sessionElementId);
                }
                break;
            default:
                break;
        }

        // detect checkpoint context saver
        if (
                !failed
                && executionContext.getChainRuntimeProperties().isDptEventsEnabled()
                && executionContext.getElementType().equals(CHECKPOINT)
                && !exchange.getProperty(CamelConstants.Properties.CHECKPOINT_IS_TRIGGER_STEP, false, Boolean.class)
                && sessionsKafkaReportingService.isPresent()
        ) {
            String parentSessionId = exchange.getProperty(
                    CamelConstants.Properties.CHECKPOINT_INTERNAL_PARENT_SESSION_ID, String.class);
            String originalSessionId = exchange.getProperty(
                    CamelConstants.Properties.CHECKPOINT_INTERNAL_ORIGINAL_SESSION_ID, String.class);
            sessionsKafkaReportingService.get().addToQueue(exchange, sessionId,
                    originalSessionId, parentSessionId, EventSourceType.SESSION_CHECKPOINT_PASSED);
        }
    }

    private void logBeforeStepStarted(
            Exchange exchange,
            String stepName,
            String elementId,
            ChainElementType elementType
    ) {
        ChainRuntimeProperties chainRuntimeProperties = chainRuntimePropertiesService.getRuntimeProperties(exchange);
        LogLoggingLevel logLoggingLevel = chainRuntimeProperties.getLogLoggingLevel();
        switch (elementType) {
            case SERVICE_CALL:
                if (CamelNames.REQUEST_ATTEMPT_STEP_PREFIX.equals(stepName)) {
                    chainLogger.logRequestAttempt(exchange, elementId);
                } else if (CamelNames.REQUEST_PREFIX.equals(stepName)) {
                    ServiceCallInfo serviceCallInfo = MetadataUtil.getBeanForElement(exchange, elementId, ServiceCallInfo.class);
                    chainLogger.logRequest(
                            exchange,
                            serviceCallInfo.getExternalServiceAddress(),
                            serviceCallInfo.getExternalServiceEnvironmentName()
                    );
                }
                break;
            default:
                break;
        }
    }

    private void handleElementBeforeProcess(Exchange exchange, String elementId, ChainElementType elementType) {
        switch (elementType) {
            case SERVICE_CALL:
                ServiceCallInfo serviceCallInfo = MetadataUtil.getBeanForElement(exchange, elementId, ServiceCallInfo.class);
                exchange.setProperty(ChainProperties.EXTERNAL_SERVICE_ADDRESS_PROP,
                        serviceCallInfo.getExternalServiceAddress());
                exchange.setProperty(ChainProperties.EXTERNAL_SERVICE_ENV_NAME_PROP,
                        serviceCallInfo.getExternalServiceEnvironmentName());
                break;
            default:
                break;
        }
    }

    public void logAfterStepFinished(
            Exchange exchange,
            ChainExecutionContext executionContext
    ) {
        switch (executionContext.getElementType()) {
            case SERVICE_CALL:
                if (CamelNames.REQUEST_ATTEMPT_STEP_PREFIX
                        .equals(executionContext.getStepName())) {
                    chainLogger.logRetryRequestAttempt(exchange, executionContext.getElementInfo().getId());
                }
                break;
            default:
                break;
        }
    }

    public void finishCheckpointSession(
            Exchange exchange,
            String sessionId,
            ExecutionStatus executionStatus,
            long duration
    ) {
        SessionInfo checkpointSession = checkpointSessionService.findSession(sessionId);
        if (checkpointSession != null) {
            if (executionStatus == ExecutionStatus.COMPLETED_WITH_ERRORS) {
                checkpointSession.setExecutionStatus(executionStatus);
                checkpointSession.setFinished(Timestamp.from(new Date().toInstant()));
                checkpointSession.setDuration(duration);
                checkpointSessionService.saveSession(checkpointSession);

                setLoggerContext(exchange, null);

                chainLogger.warn(
                        "Chain session completed with errors. You can retry the session with "
                                + "checkpoint elements");
            } else {
                try {
                    boolean isRootSession =
                            exchange.getProperty(
                                    CamelConstants.Properties.CHECKPOINT_INTERNAL_PARENT_SESSION_ID,
                                    String.class) == null;
                    checkpointSessionService.removeAllRelatedCheckpoints(checkpointSession.getId(),
                            isRootSession);
                } catch (Exception e) {
                    log.error("Failed to run checkpoint cleanup", e);
                }
            }
        }
    }

    private void initOrActivatePropagatedContext(Exchange exchange) {
        final Map<String, Object> contextSnapshot = (Map<String, Object>) exchange.getProperty(
                CamelConstants.Properties.REQUEST_CONTEXT_PROPAGATION_SNAPSHOT);
        Map<String, Object> exchangeHeaders = exchange.getMessage().getHeaders();

        long currentThreadId = Thread.currentThread().getId();
        if (contextSnapshot != null) {
            // restore context for new threads and remember thread id with initialized context
            Set<Long> contextInitMarkers = getContextInitMarkers(exchange);
            if (!contextInitMarkers.contains(currentThreadId)) {
                log.debug("Detected new thread '{}' with empty context",
                        Thread.currentThread().getName());
                exchangeContextPropagation.activateContextSnapshot(contextSnapshot);
                contextInitMarkers.add(currentThreadId);
            }
        } else {
            // initial exchange created
            exchangeContextPropagation.initRequestContext(exchangeHeaders);
            getContextInitMarkers(exchange).add(currentThreadId);
            log.debug("New exchange created in thread '{}'", Thread.currentThread().getName());

            Object authorization = exchangeHeaders.get(HttpHeaders.AUTHORIZATION);
            exchangeContextPropagation.removeContextHeaders(exchangeHeaders);
            if (nonNull(authorization)) {
                exchangeHeaders.put(HttpHeaders.AUTHORIZATION, authorization);
            }

            Map<String, Object> snapshot = exchangeContextPropagation.createContextSnapshot();
            exchange.setProperty(CamelConstants.Properties.REQUEST_CONTEXT_PROPAGATION_SNAPSHOT, snapshot);

            this.exchangePropertyService.initAdditionalExchangeProperties(exchange);
        }
    }

    private Set<Long> getContextInitMarkers(Exchange exchange) {
        Set<Long> contextInitMarkers = exchange.getProperty(
                CamelConstants.Properties.CONTEXT_INIT_MARKERS,
                Set.class);
        if (contextInitMarkers == null) {
            HashSet<Long> newSet = new HashSet<>();
            exchange.setProperty(CamelConstants.Properties.CONTEXT_INIT_MARKERS, newSet);
            contextInitMarkers = newSet;
        }
        return contextInitMarkers;
    }

    private void setLoggerContext(Exchange exchange, @Nullable String nodeId) {
        chainLogger.setLoggerContext(exchange, nodeId, tracingService.isTracingEnabled());
    }

    private void setFailedElementId(Exchange exchange, ElementInfo elementInfo) {
        if (Boolean.TRUE.equals(exchange.getProperty(CamelConstants.Properties.ELEMENT_FAILED, Boolean.class))) {
            exchange.setProperty(ChainProperties.FAILED_ELEMENT_NAME, elementInfo.getName());
            exchange.setProperty(ChainProperties.FAILED_ELEMENT_ID, elementInfo.getId());
            exchange.setProperty(CamelConstants.Properties.ELEMENT_WARNING, Boolean.FALSE);
            DebuggerUtils.setOverallWarning(exchange, false);
        } else if (DebuggerUtils.isFailedOperation(exchange)
                && exchange.getProperties().get(CamelConstants.Properties.LAST_EXCEPTION) != exchange.getException()) {
            exchange.getProperties().put(CamelConstants.Properties.LAST_EXCEPTION, exchange.getException());
            exchange.getProperties().put(CamelConstants.Properties.LAST_EXCEPTION_ERROR_CODE, ErrorCode.match(exchange.getException()));

            exchange.setProperty(ChainProperties.FAILED_ELEMENT_NAME, elementInfo.getName());
            exchange.setProperty(ChainProperties.FAILED_ELEMENT_ID, elementInfo.getId());
            exchange.setProperty(CamelConstants.Properties.ELEMENT_WARNING, Boolean.FALSE);
            DebuggerUtils.setOverallWarning(exchange, false);
        }
    }

    private void checkExecutionTimeout(Exchange exchange) {
        long timeoutAfter = exchange.getProperty(CamelConstants.Properties.CHAIN_TIME_OUT_AFTER, 0, Long.class);
        if (timeoutAfter <= 0) {
            return;
        }
        long startTime = exchange.getProperty(CamelConstants.Properties.START_TIME_MS, Long.class);
        long duration = System.currentTimeMillis() - startTime;
        boolean isTimedOut = exchange.getProperty(CamelConstants.Properties.CHAIN_TIMED_OUT, false, Boolean.class);

        if (duration > timeoutAfter && !isTimedOut) {
            Exception exception = new ChainExecutionTimeoutException("Chain execution timed out after " + duration
                    + " ms. Desired limit is " + timeoutAfter + " ms.");
            exchange.setProperty(CamelConstants.Properties.CHAIN_TIMED_OUT, true);
            exchange.setException(exception);
        }
    }
}
