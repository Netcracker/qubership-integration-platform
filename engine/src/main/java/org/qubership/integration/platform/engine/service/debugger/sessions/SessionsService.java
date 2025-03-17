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

package org.qubership.integration.platform.engine.service.debugger.sessions;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.Session;
import org.qubership.integration.platform.engine.model.SessionElementProperty;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.logging.SessionsLoggingLevel;
import org.qubership.integration.platform.engine.model.opensearch.ExceptionInfo;
import org.qubership.integration.platform.engine.model.opensearch.SessionElementElastic;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.debugger.util.DebuggerUtils;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.qubership.integration.platform.engine.util.IdentifierUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;

import static org.qubership.integration.platform.engine.camel.CorrelationIdSetter.CORRELATION_ID;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties.HAS_INTERMEDIATE_PARENTS;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties.REUSE_ORIGINAL_ID;

@Slf4j
@Component
public class SessionsService {

    private final PayloadExtractor extractor;

    private final OpenSearchWriter writer;

    private final Random random = new Random();

    @Value("${qip.sessions.sampler.probabilistic}")
    private double samplerProbabilistic;

    @Autowired
    public SessionsService(PayloadExtractor extractor, OpenSearchWriter writer) {
        this.extractor = extractor;
        this.writer = writer;
    }

    public Session startSession(
        Exchange exchange,
        CamelDebuggerProperties dbgProperties,
        String sessionId,
        String parentSessionId,
        String startTime,
        String currentDomain,
        String currentEngineAddress
    ) {
        SessionsLoggingLevel sessionLevel = dbgProperties.getRuntimeProperties(exchange)
            .calculateSessionLevel(exchange);
        Session session = Session.builder()
            .id(sessionId)
            .externalId(
                exchange.getMessage().getHeader(Headers.EXTERNAL_SESSION_CIP_ID, String.class))
            .domain(currentDomain)
            .engineAddress(currentEngineAddress)
            .chainId(dbgProperties.getDeploymentInfo().getChainId())
            .chainName(dbgProperties.getDeploymentInfo().getChainName())
            .started(startTime)
            .executionStatus(ExecutionStatus.IN_PROGRESS)
            .loggingLevel(sessionLevel.toString())
            .snapshotName(dbgProperties.getDeploymentInfo().getSnapshotName())
            .parentSessionId(parentSessionId)
            .build();

        if (sessionLevel != SessionsLoggingLevel.OFF) {
            writer.putSessionToCache(session);
        }
        return session;
    }

    public void finishSession(
        Exchange exchange,
        CamelDebuggerProperties dbgProperties,
        ExecutionStatus executionStatus,
        String finishTime,
        long duration
    ) {
        String sessionId = exchange.getProperty(Properties.SESSION_ID, String.class);
        boolean cacheCleared = false;

        try {
            SessionsLoggingLevel sessionLevel = dbgProperties.getRuntimeProperties(exchange)
                .calculateSessionLevel(exchange);

            if (sessionLevel != SessionsLoggingLevel.OFF) {
                Pair<ReadWriteLock, Session> sessionPair = writer.getSessionFromCache(sessionId);

                if (sessionPair != null && sessionPair.getRight() != null) {
                    ReadWriteLock sessionLock = sessionPair.getLeft();
                    Session session = sessionPair.getRight();

                    sessionLock.writeLock().lock();
                    try {
                        if (dbgProperties.containsElementProperty(ChainProperties.EXECUTION_STATUS)) {
                            executionStatus = ExecutionStatus.computeHigherPriorityStatus(
                                ExecutionStatus.valueOf(
                                    dbgProperties.getElementProperty(ChainProperties.EXECUTION_STATUS)
                                        .get(
                                            ChainProperties.EXECUTION_STATUS)),
                                executionStatus);
                        }
                        session.setExecutionStatus(executionStatus);
                        session.setFinished(finishTime);
                        session.setDuration(duration);

                        // update general session data for every related element
                        Collection<SessionElementElastic> elements =
                            writer.getSessionElementsFromCache(sessionId);
                        for (SessionElementElastic element : elements) {
                            if (element != null) {

                                // change inProgress element to cancelled/unknown status
                                if (element.getExecutionStatus() == ExecutionStatus.IN_PROGRESS) {
                                    element.setExecutionStatus(
                                        ExecutionStatus.CANCELLED_OR_UNKNOWN);
                                }

                                updateSessionInfoForElements(session, element);
                                writer.scheduleElementToLog(element);
                            }
                        }
                        writer.clearSessionCache(sessionId);
                        cacheCleared = true;
                    } finally {
                        sessionLock.writeLock().unlock();
                    }
                }
            }
        } finally {
            if (!cacheCleared) {
                writer.clearSessionCache(sessionId);
            }
        }
    }

    public void logSessionStepElementBefore(
        Exchange exchange,
        CamelDebuggerProperties dbgProperties,
        String sessionId,
        String sessionElementId,
        String stepId,
        String stepChainElementId
    ) {
        SessionElementElastic sessionElement = buildSessionStepElementBefore(
            exchange, dbgProperties, sessionId, sessionElementId, stepId, stepChainElementId);

        writer.scheduleElementToLogAndCache(sessionElement);
    }

    @NotNull
    private SessionElementElastic buildSessionStepElementBefore(
        Exchange exchange,
        CamelDebuggerProperties dbgProperties,
        String sessionId,
        String sessionElementId,
        String stepId,
        String stepChainElementId
    ) {

        Map<String, SessionElementProperty> propertiesForLogging = extractor.extractExchangePropertiesForLogging(
            exchange, dbgProperties.getMaskedFields(), dbgProperties.getRuntimeProperties(exchange)
                .isMaskingEnabled());
        Map<String, String> contextHeaders = extractor.extractContextForLogging(
            dbgProperties.getMaskedFields(), dbgProperties.getRuntimeProperties(exchange)
                .isMaskingEnabled());

        SessionElementElastic sessionElement = SessionElementElastic.builder()
            .id(sessionElementId)
            .elementName(stepId)
            .sessionId(sessionId)
            .started(LocalDateTime.now().toString())
            .bodyBefore(extractor.extractBodyForLogging(exchange, dbgProperties.getMaskedFields(),
                dbgProperties.getRuntimeProperties(exchange)
                    .isMaskingEnabled()))
            .headersBefore(
                extractor.convertToJson(
                    extractor.extractHeadersForLogging(exchange, dbgProperties.getMaskedFields(),
                        dbgProperties.getRuntimeProperties(exchange)
                            .isMaskingEnabled())))
            .propertiesBefore(extractor.convertToJson(propertiesForLogging))
            .contextBefore(extractor.convertToJson(contextHeaders))
            .executionStatus(ExecutionStatus.IN_PROGRESS)
            .build();

        Map<String, String> elementStepProperties = dbgProperties.getElementProperty(stepId);
        sessionElement.setActualElementChainId(getActualChainId(dbgProperties, stepId, stepChainElementId));

        updateSessionInfoForElements(exchange, sessionElement);

        if (IdentifierUtils.isValidUUID(stepId)) {
            if (Objects.requireNonNull(elementStepProperties).containsKey(ChainProperties.WIRE_TAP_ID)) {
                List<String> parentIds = Arrays.stream(elementStepProperties.get(ChainProperties.WIRE_TAP_ID).split(","))
                        .map(String::trim)
                        .toList();
                for (String id : parentIds) {
                    if (((Map<String, String>) exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP)).
                            containsKey(id)) {

                        sessionElement.setParentElementId(((Map<String, String>) exchange.getProperty(
                                Properties.ELEMENT_EXECUTION_MAP)).get(id));
                    }
                }
            }
            else {
                sessionElement.setParentElementId(extractParentId(exchange, sessionId, elementStepProperties));
            }
            sessionElement.setChainElementId(stepId);
            sessionElement.setElementName(
                elementStepProperties.get(ChainProperties.ELEMENT_NAME));
            sessionElement.setCamelElementName(elementStepProperties.get(
                ChainProperties.ELEMENT_TYPE));
        } else {
            sessionElement.setParentElementId(
                (String) exchange.getProperty(Properties.STEPS, Deque.class).peek());
            sessionElement.setElementName(stepId);

            if (!StringUtils.isEmpty(stepChainElementId)) {
                sessionElement.setChainElementId(stepChainElementId);
                sessionElement.setCamelElementName(
                    dbgProperties.getElementProperty(stepChainElementId).get(
                        ChainProperties.ELEMENT_TYPE));
            }
        }
        return sessionElement;
    }

    private static String getActualChainId(CamelDebuggerProperties dbgProperties, String stepId, String stepChainElementId) {
        Map<String, String> elementStepProperties = dbgProperties.getElementProperty(stepId);
        if (elementStepProperties == null && StringUtils.isNotEmpty(stepChainElementId)) {
            // Get properties from parent element if current element doesn't have it
            elementStepProperties = dbgProperties.getElementProperty(stepChainElementId);
        }
        if (elementStepProperties != null && elementStepProperties.get(ChainProperties.ACTUAL_ELEMENT_CHAIN_ID) != null) {
            String stepNameForActualChainIdOverride = elementStepProperties.get(ChainProperties.ACTUAL_CHAIN_OVERRIDE_STEP_NAME_FIELD);
            if (stepNameForActualChainIdOverride == null || stepNameForActualChainIdOverride.equals(stepId)) {
                return elementStepProperties.get(ChainProperties.ACTUAL_ELEMENT_CHAIN_ID);
            }
        }
        return null;
    }

    public void logSessionElementBefore(Exchange exchange,
        CamelDebuggerProperties dbgProperties, String sessionId,
        String sessionElementId, String nodeId,
        String bodyForLogging, Map<String, String> headersForLogging,
        Map<String, String> contextHeaders,
        Map<String, SessionElementProperty> exchangePropertiesForLogging
    ) {
        SessionElementElastic sessionElement = buildSessionElementBefore(
            exchange, dbgProperties, sessionId, sessionElementId, nodeId, bodyForLogging,
            headersForLogging,
            contextHeaders, exchangePropertiesForLogging);

        writer.scheduleElementToLogAndCache(sessionElement);
    }

    private SessionElementElastic buildSessionElementBefore(Exchange exchange,
        CamelDebuggerProperties dbgProperties, String sessionId,
        String sessionElementId, String nodeId,
        String bodyForLogging, Map<String, String> headersForLogging,
        Map<String, String> contextHeaders,
        Map<String, SessionElementProperty> propertiesForLogging
    ) {
        Map<String, String> elementProperties = dbgProperties.getElementProperty(nodeId);
        String parentElementId = extractParentId(exchange, sessionId, elementProperties);

        SessionElementElastic sessionElement = SessionElementElastic.builder()
            .id(sessionElementId)
            .chainElementId(nodeId)
            .elementName(elementProperties.get(ChainProperties.ELEMENT_NAME))
            .camelElementName(elementProperties.get(ChainProperties.ELEMENT_TYPE))
            .sessionId(sessionId)
            .parentElementId(
                SessionsLoggingLevel.ERROR == dbgProperties.getRuntimeProperties(exchange)
                    .calculateSessionLevel(exchange) ? null : parentElementId)
            .started(LocalDateTime.now().toString())
            .bodyBefore(bodyForLogging)
            .headersBefore(extractor.convertToJson(headersForLogging))
            .propertiesBefore(extractor.convertToJson(propertiesForLogging))
            .contextBefore(extractor.convertToJson(contextHeaders))
            .executionStatus(ExecutionStatus.IN_PROGRESS)
            .build();

        updateSessionInfoForElements(exchange, sessionElement);

        if (Objects.requireNonNull(elementProperties).containsKey(ChainProperties.WIRE_TAP_ID)) {
            List<String> parentIds = Arrays.stream(elementProperties.get(ChainProperties.WIRE_TAP_ID).split(","))
                    .map(String::trim)
                    .toList();
            for (String id : parentIds) {
                if (((Map<String, String>) exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP)).containsKey(id)) {
                    sessionElement.setParentElementId(((Map<String, String>) exchange.getProperty(
                            Properties.ELEMENT_EXECUTION_MAP)).get(id));
                }
            }
        }

        String splitIdChain = (String) exchange.getProperty(Properties.SPLIT_ID_CHAIN);
        ((Map<String, String>) exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP)).put(
                DebuggerUtils.getNodeIdForExecutionMap(nodeId, splitIdChain), sessionElementId);
        return sessionElement;
    }

    public void logSessionElementAfter(Exchange exchange, Exception externalException,
        String sessionId, String sessionElementId,
        Set<String> maskedFields, boolean maskingEnabled) {
        logSessionElementAfter(
            exchange,
            externalException,
            writer.getSessionElementFromCache(sessionId, sessionElementId),
            extractor.extractBodyForLogging(exchange, maskedFields, maskingEnabled),
            extractor.extractHeadersForLogging(exchange, maskedFields, maskingEnabled),
            extractor.extractContextForLogging(maskedFields, maskingEnabled),
            extractor.extractExchangePropertiesForLogging(exchange, maskedFields, maskingEnabled));
    }

    public void logSessionElementAfter(Exchange exchange,
        Exception externalException,
        String sessionId,
        String sessionElementId,
        String bodyForLogging,
        Map<String, String> headersForLogging,
        Map<String, String> contextHeaders,
        Map<String, SessionElementProperty> exchangePropertiesForLogging
    ) {
        logSessionElementAfter(
            exchange,
            externalException,
            writer.getSessionElementFromCache(sessionId, sessionElementId),
            bodyForLogging, headersForLogging,
            contextHeaders, exchangePropertiesForLogging);
    }

    private void logSessionElementAfter(
        Exchange exchange,
        Exception externalException,
        SessionElementElastic sessionElement,
        String bodyForLogging,
        Map<String, String> headersForLogging,
        Map<String, String> contextHeaders,
        Map<String, SessionElementProperty> propertiesForLogging
    ) {
        if (sessionElement == null) {
            return;
        }

        String finished = LocalDateTime.now().toString();
        sessionElement.setFinished(finished);
        sessionElement.setBodyAfter(bodyForLogging);
        sessionElement.setHeadersAfter(extractor.convertToJson(headersForLogging));
        sessionElement.setPropertiesAfter(extractor.convertToJson(propertiesForLogging));
        sessionElement.setContextAfter(extractor.convertToJson(contextHeaders));
        Exception exception = exchange.getException() != null ? exchange.getException() : externalException;

        if (ChainElementType.isExceptionHandleElement(
            ChainElementType.fromString(sessionElement.getCamelElementName())) &&
            exception == null &&
            Boolean.TRUE.equals(exchange.getProperty(Properties.ELEMENT_WARNING, Boolean.class))) {
            sessionElement.setExecutionStatus(ExecutionStatus.COMPLETED_WITH_WARNINGS);
        } else {
            sessionElement.setExecutionStatus(exception != null
                ? ExecutionStatus.COMPLETED_WITH_ERRORS
                : ExecutionStatus.COMPLETED_NORMALLY);
        }
        if (Boolean.TRUE.equals(exchange.getProperty(Properties.ELEMENT_FAILED, Boolean.class))) {
            sessionElement.setExecutionStatus(ExecutionStatus.COMPLETED_WITH_ERRORS);
            Exception elementException = exchange.getProperty(Exchange.EXCEPTION_CAUGHT,
                    Exception.class);
            if (elementException != null) {
                sessionElement.setExceptionInfo(new ExceptionInfo(elementException));
            }
        }
        sessionElement.setDuration(
            Duration.between(LocalDateTime.parse(sessionElement.getStarted()),
                LocalDateTime.parse(finished)).toMillis());

        if (exception != null) {
            sessionElement.setExceptionInfo(new ExceptionInfo(exception));
        }

        writer.scheduleElementToLogAndCache(sessionElement);

        if (exchange.getProperty(CORRELATION_ID) != null) {
            Pair<ReadWriteLock, Session> sessionPair = writer.getSessionFromCache(exchange.getProperty(
                    Properties.SESSION_ID).toString());
            String correlationId = String.valueOf(exchange.getProperty(CORRELATION_ID));
            if (sessionPair != null && sessionPair.getRight() != null)
                sessionPair.getRight().setCorrelationId(correlationId);
        }
    }

    /**
     * Build and put NOT STEP session element to single element cache. Used for ERROR level logging
     */
    public void putElementToSingleElCache(Exchange exchange,
        CamelDebuggerProperties dbgProperties, String sessionId,
        String sessionElementId, String nodeId,
        String bodyForLogging, Map<String, String> headersForLogging,
        Map<String, String> contextHeaders,
        Map<String, SessionElementProperty> exchangePropertiesForLogging
    ) {
        SessionElementElastic sessionElement = buildSessionElementBefore(
            exchange, dbgProperties, sessionId, sessionElementId, nodeId, bodyForLogging,
            headersForLogging,
            contextHeaders, exchangePropertiesForLogging);

        writer.putToSingleElementCache(sessionId, sessionElement);
    }

    /**
     * Build and put STEP session element to single element cache. Used for ERROR level logging
     */
    public void putStepElementToSingleElCache(Exchange exchange,
        CamelDebuggerProperties dbgProperties, String sessionId,
        String sessionElementId, String stepId,
        String stepChainElementId
    ) {
        SessionElementElastic sessionElement = buildSessionStepElementBefore(
            exchange, dbgProperties, sessionId, sessionElementId, stepId, stepChainElementId);

        writer.putToSingleElementCache(sessionId, sessionElement);
    }

    /**
     * Move element from single element cache to common sessions cache
     *
     * @return session element id
     */
    public String moveFromSingleElCacheToCommonCache(String sessionId) {
        SessionElementElastic element = writer.moveFromSingleElementCacheToElementCache(sessionId);
        return element == null ? null : element.getId();
    }

    public Boolean sessionShouldBeLogged() {
        return random.nextDouble() <= samplerProbabilistic;
    }

    private void updateSessionInfoForElements(Exchange exchange,
        SessionElementElastic sessionElement) {
        String sessionId = exchange.getProperty(Properties.SESSION_ID).toString();
        Pair<ReadWriteLock, Session> sessionPair = writer.getSessionFromCache(sessionId);

        updateSessionInfoForElements(
            sessionPair != null && sessionPair.getRight() != null ? sessionPair.getRight() : null, sessionElement);
    }

    private void updateSessionInfoForElements(Session session,
                                              SessionElementElastic sessionElement) {
        sessionElement.updateRelatedSessionData(session);
    }

    @Nullable
    private String extractParentId(Exchange exchange, String sessionId, Map<String, String> elementProperties) {
        String parentElementId = null;
        boolean hasIntermediateParents = false;
        String parentStepId = null;
        String splitPostfix = exchange.getProperty(Properties.SPLIT_ID_CHAIN, "", String.class);
        Map<String, String> executionMap = (Map<String, String>) exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP);
        if (elementProperties.containsKey(ChainProperties.PARENT_ELEMENT_ID)) {
            parentElementId = elementProperties.get(ChainProperties.PARENT_ELEMENT_ID);
            parentStepId = executionMap.get(DebuggerUtils.getNodeIdForExecutionMap(parentElementId, splitPostfix));
            if (parentStepId == null) {
                parentStepId = executionMap.get(parentElementId);
            } else {
                parentElementId = DebuggerUtils.getNodeIdForExecutionMap(parentElementId, splitPostfix);
            }
            hasIntermediateParents = Boolean.parseBoolean(elementProperties.get(HAS_INTERMEDIATE_PARENTS));
        } else if (elementProperties.containsKey(REUSE_ORIGINAL_ID)) {
            String reuseOriginalId = elementProperties.get(REUSE_ORIGINAL_ID);
            parentElementId = (String) exchange.getProperty(
                    String.format(Properties.CURRENT_REUSE_REFERENCE_PARENT_ID, reuseOriginalId));
            if (parentElementId != null) {
                parentStepId = executionMap.get(DebuggerUtils.getNodeIdForExecutionMap(parentElementId, splitPostfix));
                if (parentStepId == null) {
                    parentStepId = executionMap.get(parentElementId);
                } else {
                    parentElementId = DebuggerUtils.getNodeIdForExecutionMap(parentElementId, splitPostfix);
                }
            }
            hasIntermediateParents = Boolean.parseBoolean(
                    String.valueOf(exchange.getProperty(String.format(Properties.REUSE_HAS_INTERMEDIATE_PARENTS, reuseOriginalId)))
            );
        }

        if (StringUtils.isNotEmpty(parentElementId) && hasIntermediateParents) {
            parentStepId = findIntermediateParentId(sessionId, parentElementId, executionMap)
                    .orElse(parentStepId);
        }

        return StringUtils.isNotEmpty(parentStepId)
                ? parentStepId
                : ((Deque<String>) exchange.getProperty(Properties.STEPS)).peek(); //TODO Consider using id instead of element order only
    }

    private Optional<String> findIntermediateParentId(String sessionId, String parentChainElementId,
        Map<String, String> executionMap) {
        Optional<String> intermediateParentId = Optional.empty();
        Collection<SessionElementElastic> sessionElements = writer.getSessionElementsFromCache(sessionId);

        Queue<SessionElementElastic> elementsQueue = new LinkedList<>();
        String parentSessionElementId = executionMap.get(parentChainElementId);
        SessionElementElastic parentSessionElement = sessionElements.stream()
            .filter(sessionElement -> StringUtils.equals(sessionElement.getId(),
                parentSessionElementId))
            .findFirst()
            .orElse(null);
        if (parentSessionElement != null) {
            intermediateParentId = Optional.ofNullable(parentSessionElement.getId());
            elementsQueue.offer(parentSessionElement);
        }

        while (!elementsQueue.isEmpty()) {
            final SessionElementElastic currentParentElement = elementsQueue.poll();
            Optional<SessionElementElastic> foundChildElement = sessionElements.stream()
                .filter(sessionElement -> StringUtils.equals(currentParentElement.getId(),
                    sessionElement.getParentElementId())
                    && StringUtils.equals(parentSessionElement.getChainElementId(),
                    sessionElement.getChainElementId())
                    && executionMap.containsValue(sessionElement.getId()))
                .filter(sessionElement -> sessionElement.getExecutionStatus()
                    == ExecutionStatus.IN_PROGRESS)
                .findAny();
            if (foundChildElement.isPresent()) {
                SessionElementElastic intermediateSessionElement = foundChildElement.get();
                intermediateParentId = Optional.ofNullable(intermediateSessionElement.getId());
                elementsQueue.offer(intermediateSessionElement);
            }
        }

        return intermediateParentId;
    }
}
