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

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.NamedNode;
import org.apache.camel.Processor;
import org.apache.camel.impl.debugger.DefaultDebugger;
import org.apache.camel.model.StepDefinition;
import org.apache.camel.spi.CamelEvent.*;
import org.apache.hc.core5.http.HttpHeaders;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeContextPropagation;
import org.qubership.integration.platform.engine.configuration.ServerConfiguration;
import org.qubership.integration.platform.engine.errorhandling.ChainExecutionTimeoutException;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.Session;
import org.qubership.integration.platform.engine.model.SessionElementProperty;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelNames;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.logging.ElementRetryProperties;
import org.qubership.integration.platform.engine.model.logging.LogLoggingLevel;
import org.qubership.integration.platform.engine.model.logging.SessionsLoggingLevel;
import org.qubership.integration.platform.engine.model.sessionsreporting.EventSourceType;
import org.qubership.integration.platform.engine.persistence.shared.entity.Checkpoint;
import org.qubership.integration.platform.engine.persistence.shared.entity.SessionInfo;
import org.qubership.integration.platform.engine.service.CheckpointSessionService;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.debugger.kafkareporting.SessionsKafkaReportingService;
import org.qubership.integration.platform.engine.service.debugger.logging.ChainLogger;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsService;
import org.qubership.integration.platform.engine.service.debugger.sessions.SessionsService;
import org.qubership.integration.platform.engine.service.debugger.tracing.TracingService;
import org.qubership.integration.platform.engine.service.debugger.util.DebuggerUtils;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.qubership.integration.platform.engine.util.IdentifierUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

import static java.util.Objects.nonNull;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.*;
import static org.qubership.integration.platform.engine.util.CheckpointUtils.*;

@Slf4j
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class CamelDebugger extends DefaultDebugger {

    private final ServerConfiguration serverConfiguration;
    private final TracingService tracingService;
    private final CheckpointSessionService checkpointSessionService;
    private final MetricsService metricsService;
    private final ChainLogger chainLogger;
    private final Optional<SessionsKafkaReportingService> sessionsKafkaReportingService;
    private final SessionsService sessionsService;
    private final PayloadExtractor payloadExtractor;
    private final VariablesService variablesService;
    private final CamelDebuggerPropertiesService propertiesService;
    private final Optional<CamelExchangeContextPropagation> exchangeContextPropagation;
    @Setter
    @Getter
    private String deploymentId;

    @Autowired
    public CamelDebugger(
            ServerConfiguration serverConfiguration,
            TracingService tracingService,
            CheckpointSessionService checkpointSessionService,
            MetricsService metricsService,
            ChainLogger chainLogger,
            Optional<SessionsKafkaReportingService> sessionsKafkaReportingService,
            SessionsService sessionsService,
            PayloadExtractor payloadExtractor,
            VariablesService variablesService,
            CamelDebuggerPropertiesService propertiesService,
            Optional<CamelExchangeContextPropagation> exchangeContextPropagation
    ) {
        this.serverConfiguration = serverConfiguration;
        this.tracingService = tracingService;
        this.checkpointSessionService = checkpointSessionService;
        this.metricsService = metricsService;
        this.chainLogger = chainLogger;
        this.sessionsKafkaReportingService = sessionsKafkaReportingService;
        this.sessionsService = sessionsService;
        this.payloadExtractor = payloadExtractor;
        this.variablesService = variablesService;
        this.propertiesService = propertiesService;
        this.exchangeContextPropagation = exchangeContextPropagation;
    }

    @Override
    public boolean onEvent(Exchange exchange, ExchangeEvent event) {
        CamelDebuggerProperties dbgProperties = getRelatedProperties(exchange);

        switch (event) {
            case ExchangeCreatedEvent ev -> exchangeCreated(exchange, dbgProperties);
            case StepStartedEvent ev -> stepStarted(exchange, (StepEvent) event, dbgProperties);
            case StepCompletedEvent ev -> stepFinished(exchange, (StepEvent) event, dbgProperties, false);
            case StepFailedEvent ev -> stepFinished(exchange, (StepEvent) event, dbgProperties, true);
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
        CamelDebuggerProperties dbgProperties = getRelatedProperties(exchange);

        initOrActivatePropagatedContext(exchange);

        SessionsLoggingLevel sessionLevel = dbgProperties.getRuntimeProperties(exchange)
                .calculateSessionLevel(exchange);
        LogLoggingLevel logLoggingLevel = dbgProperties.getRuntimeProperties(exchange)
                .getLogLoggingLevel();

        String sessionId = exchange.getProperty(CamelConstants.Properties.SESSION_ID).toString();
        String nodeId = definition.getId();
        boolean sessionShouldBeLogged = exchange.getProperty(
                CamelConstants.Properties.SESSION_SHOULD_BE_LOGGED,
                Boolean.class);

        setLoggerContext(exchange, dbgProperties, nodeId);

        if (exchange.getProperty(CamelConstants.Properties.ELEMENT_EXECUTION_MAP) == null) {
            exchange.setProperty(CamelConstants.Properties.ELEMENT_EXECUTION_MAP, new ConcurrentHashMap<>());
        }

        if (CamelConstants.CUSTOM_STEP_ID_PATTERN.matcher(nodeId).matches()) {
            String stepName = DebuggerUtils.getStepNameFormatted(nodeId);
            String elementId = DebuggerUtils.getStepChainElementId(nodeId);
            ChainElementType elementType = ChainElementType.fromString(
                    dbgProperties.getElementProperty(elementId).get(ChainProperties.ELEMENT_TYPE));
            logBeforeStepStarted(exchange, dbgProperties, stepName, elementId, elementType);
            handleElementBeforeProcess(exchange, dbgProperties, elementId, elementType);
        }

        if (IdentifierUtils.isValidUUID(nodeId)) {
            if (tracingService.isTracingEnabled() && dbgProperties.containsElementProperty(
                    nodeId)) {
                tracingService.addElementTracingTags(exchange, nodeId, dbgProperties);
            }

            ChainElementType chainElementType = ChainElementType.fromString(
                    dbgProperties.getElementProperty(nodeId).get(ChainProperties.ELEMENT_TYPE));

            Map<String, String> headersForLogging = Collections.emptyMap();
            Map<String, SessionElementProperty> exchangePropertiesForLogging = Collections.emptyMap();
            String bodyForLogging = null;

            boolean isElementForSessionsLevel = ChainElementType.isElementForInfoSessionsLevel(
                    chainElementType);

            if ((sessionShouldBeLogged && SessionsLoggingLevel.hasPayload(sessionLevel, isElementForSessionsLevel))
                    || logLoggingLevel.isInfoLevel()) {
                headersForLogging = payloadExtractor.extractHeadersForLogging(exchange,
                        dbgProperties.getMaskedFields(),
                        dbgProperties.getRuntimeProperties(exchange)
                                .isMaskingEnabled());
                bodyForLogging = payloadExtractor.extractBodyForLogging(exchange,
                        dbgProperties.getMaskedFields(),
                        dbgProperties.getRuntimeProperties(exchange)
                                .isMaskingEnabled());
                exchangePropertiesForLogging = payloadExtractor.extractExchangePropertiesForLogging(
                        exchange, dbgProperties.getMaskedFields(),
                        dbgProperties.getRuntimeProperties(exchange)
                                .isMaskingEnabled());
            }

            if (!(definition instanceof StepDefinition)) { // not step

                String sessionElementId = UUID.randomUUID().toString();
                switch (sessionLevel) {
                    case ERROR:
                        putElementToSingleElCache(exchange, dbgProperties, sessionId,
                                sessionElementId, nodeId,
                                bodyForLogging, headersForLogging, exchangePropertiesForLogging);
                        break;
                    case INFO:
                        putElementToSingleElCache(exchange, dbgProperties, sessionId,
                                sessionElementId, nodeId,
                                bodyForLogging, headersForLogging, exchangePropertiesForLogging);
                        if (!isElementForSessionsLevel) {
                            break;
                        }
                    case DEBUG:
                        if (sessionShouldBeLogged) {
                            sessionsService.logSessionElementBefore(
                                    exchange,
                                    dbgProperties, sessionId,
                                    sessionElementId, nodeId,
                                    bodyForLogging, headersForLogging,
                                    payloadExtractor.extractContextForLogging(
                                            dbgProperties.getMaskedFields(),
                                            dbgProperties.getRuntimeProperties(exchange)
                                                    .isMaskingEnabled()),
                                    exchangePropertiesForLogging);
                        }
                        break;
                    default:
                        break;
                }
            }

            try {
                chainLogger.logBeforeProcess(
                        exchange,
                        dbgProperties, bodyForLogging, headersForLogging, exchangePropertiesForLogging,
                        nodeId
                );
            } catch (Exception e) {
                log.warn("Failed to log before process", e);
            }
        }

        return super.beforeProcess(exchange, processor, definition);
    }

    @Override
    @SuppressWarnings("checkstyle:FallThrough")
    public boolean afterProcess(Exchange exchange, Processor processor, NamedNode definition,
                                long timeTaken) {
        CamelDebuggerProperties dbgProperties = getRelatedProperties(exchange);

        checkExecutionTimeout(exchange);

        initOrActivatePropagatedContext(exchange);

        SessionsLoggingLevel actualSessionLevel = dbgProperties.getRuntimeProperties(exchange)
                .calculateSessionLevel(exchange);
        LogLoggingLevel logLoggingLevel = dbgProperties.getRuntimeProperties(exchange)
                .getLogLoggingLevel();

        String nodeId = definition.getId();

        setLoggerContext(exchange, dbgProperties, nodeId);

        boolean sessionShouldBeLogged = exchange.getProperty(
                CamelConstants.Properties.SESSION_SHOULD_BE_LOGGED,
                Boolean.class);

        if (IdentifierUtils.isValidUUID(nodeId)) {
            Map<String, String> elementProperties = dbgProperties.getElementProperty(nodeId);
            ChainElementType chainElementType = ChainElementType.fromString(
                    elementProperties.get(ChainProperties.ELEMENT_TYPE));

            Map<String, String> headersForLogging = Collections.emptyMap();
            Map<String, SessionElementProperty> exchangePropertiesForLogging = Collections.emptyMap();
            String bodyForLogging = null;

            boolean isElementForSessionsLevel = ChainElementType.isElementForInfoSessionsLevel(
                    chainElementType);

            setFailedElementId(exchange, elementProperties);

            if ((sessionShouldBeLogged && SessionsLoggingLevel.hasPayload(actualSessionLevel, isElementForSessionsLevel))
                    || logLoggingLevel.isInfoLevel()
                    || DebuggerUtils.isFailedOperation(exchange)) {
                headersForLogging = payloadExtractor.extractHeadersForLogging(exchange,
                        dbgProperties.getMaskedFields(), dbgProperties.getRuntimeProperties(exchange)
                                .isMaskingEnabled());
                bodyForLogging = payloadExtractor.extractBodyForLogging(exchange,
                        dbgProperties.getMaskedFields(), dbgProperties.getRuntimeProperties(exchange)
                                .isMaskingEnabled());
                exchangePropertiesForLogging = payloadExtractor.extractExchangePropertiesForLogging(
                        exchange, dbgProperties.getMaskedFields(),
                        dbgProperties.getRuntimeProperties(exchange)
                                .isMaskingEnabled());
            }

            switch (actualSessionLevel) {
                case INFO:
                    if (!isElementForSessionsLevel) {
                        break;
                    }
                case DEBUG:
                    if (sessionShouldBeLogged) {
                        String sessionId = exchange.getProperty(CamelConstants.Properties.SESSION_ID)
                                .toString();
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
                                bodyForLogging, headersForLogging,
                                payloadExtractor.extractContextForLogging(
                                        dbgProperties.getMaskedFields(),
                                        dbgProperties.getRuntimeProperties(exchange)
                                                .isMaskingEnabled()),
                                exchangePropertiesForLogging);
                    }
                    break;
                default:
                    break;
            }

            try {
                chainLogger.logAfterProcess(
                        exchange, dbgProperties, bodyForLogging, headersForLogging,
                        exchangePropertiesForLogging, nodeId,
                        timeTaken
                );
            } catch (Exception e) {
                log.warn("Failed to log after process", e);
            }
        }

        return super.afterProcess(exchange, processor, definition, timeTaken);
    }

    private void exchangeCreated(Exchange exchange, CamelDebuggerProperties dbgProperties) {
        initOrActivatePropagatedContext(exchange);

        DebuggerUtils.initInternalExchangeVariables(exchange);
        variablesService.injectVariablesToExchangeProperties(exchange.getProperties());
        exchangeStarted(exchange, dbgProperties);

        if (tracingService.isTracingEnabled()) {
            // tracing context doesn't present at this point,
            // only store custom tags to camel property
            tracingService.addChainTracingTags(exchange, dbgProperties);
        }
    }

    private void exchangeStarted(Exchange exchange, CamelDebuggerProperties dbgProperties) {
        String sessionId = exchange.getProperty(CamelConstants.Properties.SESSION_ID, String.class);

        if (sessionId == null) {
            sessionId = UUID.randomUUID().toString();
            String started = LocalDateTime.now().toString();
            Long startedMillis = System.currentTimeMillis();

            exchange.setProperty(CamelConstants.Properties.SESSION_ID, sessionId);
            exchange.setProperty(CamelConstants.Properties.SESSION_SHOULD_BE_LOGGED,
                    sessionsService.sessionShouldBeLogged());
            exchange.setProperty(IS_MAIN_EXCHANGE, true);
            exchange.setProperty(CamelConstants.Properties.START_TIME, started);
            exchange.setProperty(CamelConstants.Properties.START_TIME_MS, startedMillis);
            exchange.getProperty(CamelConstants.Properties.EXCHANGES, ConcurrentHashMap.class)
                    .put(sessionId, new ConcurrentHashMap<String, Exchange>());

            String parentSessionId = null;

            CheckpointInfo checkpointInfo = extractTriggeredCheckpointInfo(
                    exchange);

            if (checkpointInfo != null) {
                Optional<Checkpoint> checkpoint = Optional.ofNullable(
                        checkpointSessionService.findCheckpoint(
                                checkpointInfo.sessionId(), checkpointInfo.chainId(),
                                checkpointInfo.checkpointElementId()));
                parentSessionId = checkpoint.map(Checkpoint::getSession).map(SessionInfo::getId)
                        .orElse(null);
            }

            Session session = sessionsService.startSession(exchange, dbgProperties, sessionId,
                    parentSessionId, started,
                    getCurrentDomain(), getCurrentEngineAddress()
            );

            if (dbgProperties.getDeploymentInfo().isContainsCheckpointElements()) {
                checkpointSessionService.saveSession(new SessionInfo(session));
            }

            String originalSessionId = checkpointSessionService.findOriginalSessionInfo(
                            parentSessionId)
                    .map(SessionInfo::getId).orElse(parentSessionId);
            setSessionProperties(exchange, parentSessionId, originalSessionId);
            if (dbgProperties.getRuntimeProperties(exchange).isDptEventsEnabled()
                    && sessionsKafkaReportingService.isPresent()) {
                sessionsKafkaReportingService.get().addToQueue(exchange, dbgProperties, sessionId, originalSessionId, parentSessionId,
                        EventSourceType.SESSION_STARTED);
            }

            exchange.setProperty(CamelConstants.Properties.THREAD_SESSION_STATUSES, new HashMap<Long, ExecutionStatus>());
        }
        Map<String, Exchange> exchanges = (Map<String, Exchange>) exchange.getProperty(
                CamelConstants.Properties.EXCHANGES, Map.class).get(sessionId);
        if (exchanges != null) {
            exchanges.put(exchange.getExchangeId(), exchange);
        }

        // Duplicating SET_FULL_SESSION_LOGGING_LEVEL_HTTP_HEADER value to corresponding property.
        exchange.setProperty(CamelConstants.Properties.TRACE_ME,
                Boolean.valueOf(exchange.getMessage().getHeader(
                        Headers.TRACE_ME, "", String.class)));
    }

    private void exchangeFinished(Exchange exchange) {
        String sessionId = exchange.getProperty(CamelConstants.Properties.SESSION_ID).toString();
        Map<String, Exchange> exchanges = (Map<String, Exchange>) exchange.getProperty(
                CamelConstants.Properties.EXCHANGES, Map.class).get(sessionId);
        if (exchanges != null) {
            exchanges.remove(exchange.getExchangeId());
        }

        log.debug("Exchange finished in thread '{}'", Thread.currentThread().getName());
    }

    @SuppressWarnings("checkstyle:FallThrough")
    private void stepStarted(Exchange exchange,
                             StepEvent event,
                             CamelDebuggerProperties dbgProperties) {
        String sessionId = exchange.getProperty(CamelConstants.Properties.SESSION_ID).toString();
        String fullStepId = event.getStepId();
        String stepId = DebuggerUtils.getNodeIdFormatted(fullStepId);
        String stepName = DebuggerUtils.getStepNameFormatted(fullStepId);
        String stepChainElementId = DebuggerUtils.getStepChainElementId(fullStepId);

        String sessionElementId = UUID.randomUUID().toString();
        ChainElementType elementType = ChainElementType.fromString(
                dbgProperties.getElementProperty(stepId).get(
                        ChainProperties.ELEMENT_TYPE));
        boolean sessionShouldBeLogged = exchange.getProperty(
                CamelConstants.Properties.SESSION_SHOULD_BE_LOGGED,
                Boolean.class);

        metricsService.processElementStartMetrics(exchange, dbgProperties, stepId, stepName, elementType);

        switch (dbgProperties.getRuntimeProperties(exchange).calculateSessionLevel(exchange)) {
            case ERROR:
                sessionsService.putStepElementToSingleElCache(exchange, dbgProperties, sessionId,
                        sessionElementId, stepName, stepChainElementId);
                break;
            case INFO:
                if (!ChainElementType.isElementForInfoSessionsLevel(elementType)) {
                    break;
                }
            case DEBUG:
                if (sessionShouldBeLogged) {
                    sessionsService.logSessionStepElementBefore(exchange, dbgProperties, sessionId,
                            sessionElementId, stepName, stepChainElementId);

                    String executionStepId = stepName;
                    if (ChainElementType.isWrappedInStepElement(elementType)) {
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
    private void stepFinished(Exchange exchange, StepEvent event,
                              CamelDebuggerProperties dbgProperties, boolean failed) {
        String sessionId = exchange.getProperty(CamelConstants.Properties.SESSION_ID).toString();
        String fullStepId = event.getStepId();
        String stepId = DebuggerUtils.getNodeIdFormatted(fullStepId);
        String stepName = DebuggerUtils.getStepNameFormatted(fullStepId);
        ChainElementType elementType = ChainElementType.fromString(
                dbgProperties.getElementProperty(stepId).get(
                        ChainProperties.ELEMENT_TYPE));
        boolean sessionShouldBeLogged = exchange.getProperty(
                CamelConstants.Properties.SESSION_SHOULD_BE_LOGGED,
                Boolean.class);

        metricsService.processElementFinishMetrics(exchange, dbgProperties, stepId, stepName,
                elementType,
                failed);

        setFailedElementId(exchange, dbgProperties.getElementProperty(stepId));

        setLoggerContext(exchange, dbgProperties, stepId);
        logAfterStepFinished(exchange, dbgProperties, stepName, stepId, elementType);

        switch (dbgProperties.getRuntimeProperties(exchange).calculateSessionLevel(exchange)) {
            case INFO:
                if (!ChainElementType.isElementForInfoSessionsLevel(elementType)) {
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
                    sessionsService.logSessionElementAfter(exchange, null, sessionId, sessionElementId,
                            dbgProperties.getMaskedFields(),
                            dbgProperties.getRuntimeProperties(exchange)
                                    .isMaskingEnabled());
                }
                break;
            default:
                break;
        }

        // detect checkpoint context saver
        if (!failed && dbgProperties.getRuntimeProperties(exchange).isDptEventsEnabled()
                && elementType == ChainElementType.CHECKPOINT
                && !exchange.getProperty(CamelConstants.Properties.CHECKPOINT_IS_TRIGGER_STEP, false, Boolean.class)
                && sessionsKafkaReportingService.isPresent()
        ) {
            String parentSessionId = exchange.getProperty(
                    CamelConstants.Properties.CHECKPOINT_INTERNAL_PARENT_SESSION_ID, String.class);
            String originalSessionId = exchange.getProperty(
                    CamelConstants.Properties.CHECKPOINT_INTERNAL_ORIGINAL_SESSION_ID, String.class);
            sessionsKafkaReportingService.get().addToQueue(exchange, dbgProperties, sessionId, originalSessionId, parentSessionId,
                    EventSourceType.SESSION_CHECKPOINT_PASSED);
        }
    }

    private void logBeforeStepStarted(
            Exchange exchange,
            CamelDebuggerProperties dbgProperties,
            String stepName,
            String elementId,
            ChainElementType elementType
    ) {
        LogLoggingLevel logLoggingLevel = dbgProperties.getRuntimeProperties(exchange).getLogLoggingLevel();
        switch (elementType) {
            case SERVICE_CALL:
                if (CamelNames.REQUEST_ATTEMPT_STEP_PREFIX.equals(stepName)) {
                    if (logLoggingLevel.isInfoLevel()) {
                        chainLogger.logRequestAttempt(exchange, getElementRetryProperties(dbgProperties, elementId), elementId);
                    }
                } else if (CamelNames.REQUEST_PREFIX.equals(stepName)) {
                    if (logLoggingLevel.isInfoLevel()) {
                        Map<String, String> headersForLogging =
                                payloadExtractor.extractHeadersForLogging(exchange,
                                        dbgProperties.getMaskedFields(),
                                        dbgProperties.getRuntimeProperties(exchange)
                                                .isMaskingEnabled());
                        String bodyForLogging = (String) DebuggerUtils.chooseLogPayload(exchange,
                                payloadExtractor.extractBodyForLogging(exchange,
                                        dbgProperties.getMaskedFields(),
                                        dbgProperties.getRuntimeProperties(exchange)
                                                .isMaskingEnabled()),
                                dbgProperties);
                        Map<String, SessionElementProperty> exchangePropertiesForLogging =
                                payloadExtractor.extractExchangePropertiesForLogging(exchange,
                                        dbgProperties.getMaskedFields(),
                                        dbgProperties.getRuntimeProperties(exchange)
                                                .isMaskingEnabled());

                        chainLogger.logRequest(exchange, bodyForLogging, headersForLogging, exchangePropertiesForLogging,
                                dbgProperties.getElementProperty(elementId).get(
                                        ChainProperties.EXTERNAL_SERVICE_NAME),
                                dbgProperties.getElementProperty(elementId).get(
                                        ChainProperties.EXTERNAL_SERVICE_ENV_NAME));
                    }
                }
                break;
            default:
                break;
        }
    }

    private void handleElementBeforeProcess(Exchange exchange, CamelDebuggerProperties dbgProperties,
                                            String elementId, ChainElementType elementType) {
        switch (elementType) {
            case SERVICE_CALL:
                Map<String, String> elementProperties = dbgProperties.getElementProperty(elementId);
                exchange.setProperty(ChainProperties.EXTERNAL_SERVICE_NAME_PROP, elementProperties.get(
                        ChainProperties.EXTERNAL_SERVICE_NAME));
                exchange.setProperty(ChainProperties.EXTERNAL_SERVICE_ENV_NAME_PROP, elementProperties.get(
                        ChainProperties.EXTERNAL_SERVICE_ENV_NAME));
                break;
            default:
                break;
        }
    }

    public void logAfterStepFinished(
            Exchange exchange,
            CamelDebuggerProperties dbgProperties,
            String stepName,
            String elementId,
            ChainElementType elementType
    ) {
        LogLoggingLevel logLoggingLevel = dbgProperties.getRuntimeProperties(exchange).getLogLoggingLevel();
        switch (elementType) {
            case SERVICE_CALL:
                if (CamelNames.REQUEST_ATTEMPT_STEP_PREFIX.equals(stepName)) {
                    if (logLoggingLevel.isWarnLevel()) {
                        chainLogger.logRetryRequestAttempt(exchange, getElementRetryProperties(dbgProperties, elementId), elementId);
                    }
                }
                break;
            default:
                break;
        }
    }

    public void finishCheckpointSession(Exchange exchange,
                                        CamelDebuggerProperties dbgProperties, String sessionId,
                                        ExecutionStatus executionStatus, long duration
    ) {
        SessionInfo checkpointSession = checkpointSessionService.findSession(sessionId);
        if (checkpointSession != null) {
            if (executionStatus == ExecutionStatus.COMPLETED_WITH_ERRORS) {
                checkpointSession.setExecutionStatus(executionStatus);
                checkpointSession.setFinished(Timestamp.from(new Date().toInstant()));
                checkpointSession.setDuration(duration);
                checkpointSessionService.saveSession(checkpointSession);

                setLoggerContext(exchange, dbgProperties, null);

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
                exchangeContextPropagation.ifPresent(bean -> bean.activateContextSnapshot(contextSnapshot));
                contextInitMarkers.add(currentThreadId);
            }
        } else {
            // initial exchange created
            exchangeContextPropagation.ifPresent(bean -> bean.initRequestContext(exchangeHeaders));
            getContextInitMarkers(exchange).add(currentThreadId);
            log.debug("New exchange created in thread '{}'", Thread.currentThread().getName());
            exchangeContextPropagation.ifPresent(bean -> {
                Object authorization = exchangeHeaders.get(HttpHeaders.AUTHORIZATION);
                bean.removeContextHeaders(exchangeHeaders);
                if (nonNull(authorization)) {
                    exchangeHeaders.put(HttpHeaders.AUTHORIZATION, authorization);
                }
            });
            Map<String, Object> snapshot = exchangeContextPropagation.isPresent()
                    ? exchangeContextPropagation.get().createContextSnapshot()
                    : Collections.emptyMap();
            exchange.setProperty(CamelConstants.Properties.REQUEST_CONTEXT_PROPAGATION_SNAPSHOT, snapshot);
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

    private void putElementToSingleElCache(
            Exchange exchange, CamelDebuggerProperties dbgProperties, String sessionId,
            String sessionElementId, String nodeId,
            String bodyForLogging, Map<String, String> headersForLogging,
            Map<String, SessionElementProperty> exchangePropertiesForLogging
    ) {
        sessionsService.putElementToSingleElCache(
                exchange,
                dbgProperties, sessionId,
                sessionElementId, nodeId,
                bodyForLogging, headersForLogging,
                payloadExtractor.extractContextForLogging(dbgProperties.getMaskedFields(),
                        dbgProperties.getRuntimeProperties(exchange)
                                .isMaskingEnabled()),
                exchangePropertiesForLogging);
    }

    private void setLoggerContext(Exchange exchange, CamelDebuggerProperties dbgProperties,
                                  @Nullable String nodeId) {
        chainLogger.setLoggerContext(exchange, dbgProperties, nodeId,
                tracingService.isTracingEnabled());
    }

    private String getCurrentDomain() {
        return serverConfiguration.getDomain();
    }

    private String getCurrentEngineAddress() {
        return serverConfiguration.getHost();
    }

    public CamelDebuggerProperties getRelatedProperties(Exchange exchange) {
        return propertiesService.getProperties(exchange, deploymentId);
    }

    public CamelDebuggerProperties getRelatedProperties() {
        return propertiesService.getActualProperties(deploymentId);
    }

    private void setFailedElementId(Exchange exchange, Map<String, String> elementProperties) {
        if (Boolean.TRUE.equals(exchange.getProperty(CamelConstants.Properties.ELEMENT_FAILED, Boolean.class))) {
            exchange.setProperty(ChainProperties.FAILED_ELEMENT_NAME, elementProperties.get(
                    ChainProperties.ELEMENT_NAME));
            exchange.setProperty(
                    ChainProperties.FAILED_ELEMENT_ID, elementProperties.get(ChainProperties.ELEMENT_ID));
            exchange.setProperty(CamelConstants.Properties.ELEMENT_WARNING, Boolean.FALSE);
            DebuggerUtils.setOverallWarning(exchange, false);
        } else if (DebuggerUtils.isFailedOperation(exchange)
                && exchange.getProperties().get(CamelConstants.Properties.LAST_EXCEPTION) != exchange.getException()) {
            exchange.getProperties().put(CamelConstants.Properties.LAST_EXCEPTION, exchange.getException());
            exchange.getProperties().put(CamelConstants.Properties.LAST_EXCEPTION_ERROR_CODE, ErrorCode.match(exchange.getException()));

            exchange.setProperty(ChainProperties.FAILED_ELEMENT_NAME, elementProperties.get(
                    ChainProperties.ELEMENT_NAME));
            exchange.setProperty(
                    ChainProperties.FAILED_ELEMENT_ID, elementProperties.get(ChainProperties.ELEMENT_ID));
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

    private ElementRetryProperties getElementRetryProperties(CamelDebuggerProperties dbgProperties, String elementId) {
        String retryCountString = null;
        String retryDelayString = null;
        try {
            Map<String, String> elementProperties = Optional.ofNullable(dbgProperties.getElementProperty(elementId)).orElse(Collections.emptyMap());
            retryCountString = variablesService.injectVariables(elementProperties.get(SERVICE_CALL_RETRY_COUNT));
            retryDelayString = variablesService.injectVariables(elementProperties.get(SERVICE_CALL_RETRY_DELAY));
        } catch (Exception e) {
            log.error("Failed to set retry parameters for elementId: {}", elementId, e);
        }

        return new ElementRetryProperties(retryCountString, retryDelayString);
    }
}
