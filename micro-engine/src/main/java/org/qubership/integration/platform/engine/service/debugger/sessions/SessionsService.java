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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.engine.metadata.*;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.model.Session;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;
import org.qubership.integration.platform.engine.model.logging.Payload;
import org.qubership.integration.platform.engine.model.logging.SessionsLoggingLevel;
import org.qubership.integration.platform.engine.model.opensearch.ExceptionInfo;
import org.qubership.integration.platform.engine.model.opensearch.SessionElementElastic;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;
import org.qubership.integration.platform.engine.service.debugger.util.DebuggerUtils;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.qubership.integration.platform.engine.util.ExchangeUtil;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;

import static org.qubership.integration.platform.engine.camel.CorrelationIdSetter.CORRELATION_ID;

@Slf4j
@ApplicationScoped
public class SessionsService {
    private final PayloadExtractor extractor;
    private final OpenSearchWriter writer;
    private final EngineInfo engineInfo;
    private final ChainRuntimePropertiesService chainRuntimePropertiesService;
    private final Random random = new Random();

    @ConfigProperty(name = "qip.sessions.sampler.probabilistic")
    double samplerProbabilistic;

    @Inject
    public SessionsService(
            PayloadExtractor extractor,
            OpenSearchWriter writer,
            EngineInfo engineInfo,
            ChainRuntimePropertiesService chainRuntimePropertiesService
    ) {
        this.extractor = extractor;
        this.writer = writer;
        this.engineInfo = engineInfo;
        this.chainRuntimePropertiesService = chainRuntimePropertiesService;
    }

    public Session startSession(Exchange exchange, String parentSessionId) {
        LocalDateTime startTime = LocalDateTime.now();
        Long startedMillis = System.currentTimeMillis();

        DeploymentInfo deploymentInfo = MetadataUtil.getBean(exchange, DeploymentInfo.class);
        ChainInfo chainInfo = deploymentInfo.getChain();
        SnapshotInfo snapshotInfo = deploymentInfo.getSnapshot();
        ChainRuntimeProperties runtimeProperties = chainRuntimePropertiesService.getRuntimeProperties(exchange);
        SessionsLoggingLevel sessionLevel = runtimeProperties.calculateSessionLevel(exchange);
        Session session = Session.builder()
            .id(UUID.randomUUID().toString())
            .externalId(
                exchange.getMessage().getHeader(Headers.EXTERNAL_SESSION_CIP_ID, String.class))
            .domain(engineInfo.getDomain())
            .engineAddress(engineInfo.getHost())
            .chainId(chainInfo.getId())
            .chainName(chainInfo.getName())
            .started(startTime.toString())
            .executionStatus(ExecutionStatus.IN_PROGRESS)
            .loggingLevel(sessionLevel.toString())
            .snapshotName(snapshotInfo.getName())
            .parentSessionId(parentSessionId)
            .build();

        if (sessionLevel != SessionsLoggingLevel.OFF) {
            writer.putSessionToCache(session);
        }
        return session;
    }

    public void finishSession(
        Exchange exchange,
        ExecutionStatus executionStatus,
        String finishTime,
        long duration,
        long syncDuration
    ) {
        String sessionId = ExchangeUtil.getSessionId(exchange);
        boolean cacheCleared = false;

        try {
            SessionsLoggingLevel sessionLevel = chainRuntimePropertiesService
                    .getRuntimeProperties(exchange).calculateSessionLevel(exchange);
            if (sessionLevel != SessionsLoggingLevel.OFF) {
                Pair<ReadWriteLock, Session> sessionPair = writer.getSessionFromCache(sessionId);

                if (sessionPair != null && sessionPair.getRight() != null) {
                    ReadWriteLock sessionLock = sessionPair.getLeft();
                    Session session = sessionPair.getRight();

                    sessionLock.writeLock().lock();
                    try {
                        executionStatus = ExchangeUtil.getEffectiveExecutionStatus(exchange, executionStatus);

                        session.setExecutionStatus(executionStatus);
                        session.setFinished(finishTime);
                        session.setDuration(duration);
                        session.setSyncDuration(syncDuration);

                        // update general session data for every related element
                        Collection<SessionElementElastic> elements =
                            writer.getSessionElementsFromCache(sessionId);
                        for (SessionElementElastic element : elements) {
                            if (element != null) {

                                // change inProgress element to a canceled or unknown status
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
        String sessionId,
        String sessionElementId,
        String stepName,
        ElementInfo elementInfo
    ) {
        SessionElementElastic sessionElement = buildSessionStepElementBefore(
            exchange, sessionId, sessionElementId, stepName, elementInfo);

        writer.scheduleElementToLogAndCache(sessionElement);
    }

    @NotNull
    private SessionElementElastic buildSessionStepElementBefore(
        Exchange exchange,
        String sessionId,
        String sessionElementId,
        String stepName,
        ElementInfo elementInfo
    ) {
        Payload payload = extractor.extractPayload(exchange);
        SessionElementElastic sessionElement = SessionElementElastic.builder()
            .id(sessionElementId)
            .elementName(stepName)
            .sessionId(sessionId)
            .started(LocalDateTime.now().toString())
            .bodyBefore(payload.getBody())
            .headersBefore(extractor.convertToJson(payload.getHeaders()))
            .propertiesBefore(extractor.convertToJson(payload.getProperties()))
            .contextBefore(extractor.convertToJson(payload.getContext()))
            .executionStatus(ExecutionStatus.IN_PROGRESS)
            .actualElementChainId(elementInfo.getChainId())
            .chainElementId(elementInfo.getId())
            .camelElementName(elementInfo.getType())
            .build();

        updateSessionInfoForElements(exchange, sessionElement);

        if (stepName.equals(elementInfo.getId())) {
            MetadataUtil.lookupBeanForElement(exchange, stepName, WireTapInfo.class).ifPresentOrElse(
                    wireTapInfo -> {
                        for (String id : wireTapInfo.getParentIds()) {
                            if (((Map<String, String>) exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP))
                                    .containsKey(id)) {

                                sessionElement.setParentElementId(((Map<String, String>) exchange.getProperty(
                                        Properties.ELEMENT_EXECUTION_MAP)).get(id));
                            }
                        }
                    },
                    () -> sessionElement.setParentElementId(extractParentId(exchange, sessionId, elementInfo))
            );
            sessionElement.setElementName(elementInfo.getName());
        } else {
            sessionElement.setParentElementId(
                (String) exchange.getProperty(Properties.STEPS, Deque.class).peek());
        }
        return sessionElement;
    }

    public void logSessionElementBefore(
            Exchange exchange,
            String sessionId,
            String sessionElementId,
            String nodeId,
            Payload payload
    ) {
        SessionElementElastic sessionElement = buildSessionElementBefore(
            exchange, sessionId, sessionElementId, nodeId, payload);

        writer.scheduleElementToLogAndCache(sessionElement);
    }

    private SessionElementElastic buildSessionElementBefore(
            Exchange exchange,
            String sessionId,
            String sessionElementId,
            String nodeId,
            Payload payload
    ) {
        ElementInfo elementInfo = MetadataUtil.getBeanForElement(exchange, nodeId, ElementInfo.class);
        String parentElementId = extractParentId(exchange, sessionId, elementInfo);

        SessionElementElastic sessionElement = SessionElementElastic.builder()
            .id(sessionElementId)
            .chainElementId(elementInfo.getId())
            .elementName(elementInfo.getName())
            .camelElementName(elementInfo.getType())
            .actualElementChainId(elementInfo.getChainId())
            .sessionId(sessionId)
            .parentElementId(
                SessionsLoggingLevel.ERROR == chainRuntimePropertiesService.getRuntimeProperties(exchange)
                    .calculateSessionLevel(exchange) ? null : parentElementId)
            .started(LocalDateTime.now().toString())
            .bodyBefore(payload.getBody())
            .headersBefore(extractor.convertToJson(payload.getHeaders()))
            .propertiesBefore(extractor.convertToJson(payload.getProperties()))
            .contextBefore(extractor.convertToJson(payload.getContext()))
            .executionStatus(ExecutionStatus.IN_PROGRESS)
            .build();

        updateSessionInfoForElements(exchange, sessionElement);

        MetadataUtil.lookupBeanForElement(exchange, nodeId, WireTapInfo.class).ifPresent(wireTapInfo -> {
            for (String id : wireTapInfo.getParentIds()) {
                if (((Map<String, String>) exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP)).containsKey(id)) {
                    sessionElement.setParentElementId(((Map<String, String>) exchange.getProperty(
                            Properties.ELEMENT_EXECUTION_MAP)).get(id));
                }
            }
        });

        String splitIdChain = (String) exchange.getProperty(Properties.SPLIT_ID_CHAIN);
        ((Map<String, String>) exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP)).put(
                DebuggerUtils.getNodeIdForExecutionMap(nodeId, splitIdChain), sessionElementId);
        return sessionElement;
    }

    public void logSessionElementAfter(
            Exchange exchange,
            Exception externalException,
            String sessionId,
            String sessionElementId
    ) {
        logSessionElementAfter(
            exchange,
            externalException,
            writer.getSessionElementFromCache(sessionId, sessionElementId),
            extractor.extractPayload(exchange)
        );
    }

    public void logSessionElementAfter(
            Exchange exchange,
            Exception externalException,
            String sessionId,
            String sessionElementId,
            Payload payload
    ) {
        logSessionElementAfter(
            exchange,
            externalException,
            writer.getSessionElementFromCache(sessionId, sessionElementId),
            payload
        );
    }

    private void logSessionElementAfter(
        Exchange exchange,
        Exception externalException,
        SessionElementElastic sessionElement,
        Payload payload
    ) {
        if (sessionElement == null) {
            return;
        }

        String finished = LocalDateTime.now().toString();
        sessionElement.setFinished(finished);
        sessionElement.setBodyAfter(payload.getBody());
        sessionElement.setHeadersAfter(extractor.convertToJson(payload.getHeaders()));
        sessionElement.setPropertiesAfter(extractor.convertToJson(payload.getProperties()));
        sessionElement.setContextAfter(extractor.convertToJson(payload.getContext()));
        Exception exception = exchange.getException() != null ? exchange.getException() : externalException;

        if (ChainElementType.isExceptionHandleElement(ChainElementType.fromString(sessionElement.getCamelElementName()))
                    && exception == null
                    && Boolean.TRUE.equals(exchange.getProperty(Properties.ELEMENT_WARNING, Boolean.class))) {
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
            Pair<ReadWriteLock, Session> sessionPair = writer.getSessionFromCache(ExchangeUtil.getSessionId(exchange));
            String correlationId = String.valueOf(exchange.getProperty(CORRELATION_ID));
            if (sessionPair != null && sessionPair.getRight() != null) {
                sessionPair.getRight().setCorrelationId(correlationId);
            }
        }
    }

    /**
     * Build and put NOT STEP session element to single element cache. Used for ERROR level logging
     */
    public void putElementToSingleElCache(
            Exchange exchange,
            String sessionId,
            String sessionElementId,
            String nodeId,
            Payload payload
    ) {
        SessionElementElastic sessionElement = buildSessionElementBefore(
            exchange, sessionId, sessionElementId, nodeId, payload);

        writer.putToSingleElementCache(sessionId, sessionElement);
    }

    /**
     * Build and put STEP session element to single element cache. Used for ERROR level logging
     */
    public void putStepElementToSingleElCache(
            Exchange exchange,
            String sessionId,
            String sessionElementId,
            String stepName,
            ElementInfo elementInfo
    ) {
        SessionElementElastic sessionElement = buildSessionStepElementBefore(
            exchange, sessionId, sessionElementId, stepName, elementInfo);

        writer.putToSingleElementCache(sessionId, sessionElement);
    }

    /**
     * Move an element from single element cache to common sessions cache
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
        String sessionId = ExchangeUtil.getSessionId(exchange);
        Pair<ReadWriteLock, Session> sessionPair = writer.getSessionFromCache(sessionId);

        updateSessionInfoForElements(
            sessionPair != null && sessionPair.getRight() != null ? sessionPair.getRight() : null, sessionElement);
    }

    private void updateSessionInfoForElements(Session session,
                                              SessionElementElastic sessionElement) {
        sessionElement.updateRelatedSessionData(session);
    }

    @Nullable
    private String extractParentId(Exchange exchange, String sessionId, ElementInfo elementInfo) {
        String parentElementId = null;
        boolean hasIntermediateParents = false;
        String parentStepId = null;
        String splitPostfix = exchange.getProperty(Properties.SPLIT_ID_CHAIN, "", String.class);
        Map<String, String> executionMap = (Map<String, String>) exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP);
        if (StringUtils.isNotBlank(elementInfo.getParentId())) {
            parentElementId = elementInfo.getParentId();
            parentStepId = executionMap.get(DebuggerUtils.getNodeIdForExecutionMap(parentElementId, splitPostfix));
            if (parentStepId == null) {
                parentStepId = executionMap.get(parentElementId);
            } else {
                parentElementId = DebuggerUtils.getNodeIdForExecutionMap(parentElementId, splitPostfix);
            }
            hasIntermediateParents = elementInfo.isHasIntermediateParents();
        } else if (StringUtils.isNotBlank(elementInfo.getReuseId())) {
            String reuseOriginalId = elementInfo.getReuseId();
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

    private Optional<String> findIntermediateParentId(
            String sessionId,
            String parentChainElementId,
            Map<String, String> executionMap
    ) {
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
