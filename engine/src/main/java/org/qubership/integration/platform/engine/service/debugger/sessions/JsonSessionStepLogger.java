package org.qubership.integration.platform.engine.service.debugger.sessions;

import net.logstash.logback.marker.LogstashMarker;
import org.apache.camel.Exchange;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.Session;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.logging.SessionLogDetails;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.debugger.logging.LogExchangeMarkers;
import org.qubership.integration.platform.engine.service.debugger.util.DebuggerUtils;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static net.logstash.logback.marker.Markers.append;

@Component
@ConditionalOnProperty(name = "qip.logging.format", havingValue = "json", matchIfMissing = true)
public class JsonSessionStepLogger implements SessionStepLogger {

    private static final Logger LOG = LoggerFactory.getLogger(JsonSessionStepLogger.class);
    private final LogExchangeMarkers logExchangeMarkers;
    private final PayloadExtractor extractor;

    public JsonSessionStepLogger(LogExchangeMarkers logExchangeMarkers, PayloadExtractor extractor) {
        this.logExchangeMarkers = logExchangeMarkers;
        this.extractor = extractor;
    }

    @Override
    public void recordStepAfter(SessionStepLogContext ctx) {
        SessionLogDetails details = ctx.dbgProperties().getRuntimeProperties(ctx.exchange()).getSessionLogDetails();
        if (details == SessionLogDetails.OFF) {
            return;
        }
        SessionStepLogRecord logRecord = buildSessionStepLogRecord(ctx);
        if (logRecord == null) {
            return;
        }
        if (!markJsonLogged(ctx.exchange(), logRecord.elementId())) {
            return;
        }
        logAfter(logRecord, details);
    }

    @Override
    public void recordStepAfterForStep(SessionStepLogContext ctx) {
        SessionLogDetails details = ctx.dbgProperties().getRuntimeProperties(ctx.exchange()).getSessionLogDetails();
        if (details == SessionLogDetails.OFF) {
            return;
        }
        SessionStepLogRecord logRecord = buildSessionStepLogRecordForStep(ctx);
        if (logRecord == null) {
            return;
        }
        if (!markJsonLogged(ctx.exchange(), logRecord.elementId())) {
            return;
        }
        logAfter(logRecord, details);
    }

    @Override
    public void logAfter(SessionStepLogRecord logRecord, SessionLogDetails details) {
        if (details == null || details == SessionLogDetails.OFF) {
            return;
        }
        if (details == SessionLogDetails.SENDERS
                && !ChainElementType.isElementForInfoSessionsLevel(
                ChainElementType.fromString(logRecord.camelElementName()))) {
            return;
        }

        LogstashMarker markers = append("domain", logRecord.domain())
                .and(append("domain_type",
                        logRecord.domainType() == null ? null : logRecord.domainType().name()))
                .and(append("snapshot", logRecord.snapshotName()))
                .and(append("element_id", logRecord.elementId()))
                .and(append("parent_element_id", logRecord.parentElementId()))
                .and(append("element_name", logRecord.elementName()))
                .and(logExchangeMarkers.buildExchangeMarkers(
                        logRecord.bodyAfter(), logRecord.headersAfter(), logRecord.propertiesAfter()));

        if (LOG.isInfoEnabled()) {
            LOG.info(markers, buildMessage(logRecord));
        }
    }

    private String buildMessage(SessionStepLogRecord logRecord) {
        return String.format("Session step finished with status %s",
                logRecord.executionStatus());
    }

    @Override
    public void logSessionStart(Session session, SessionLogDetails details) {
        if (details == null || details == SessionLogDetails.OFF) {
            return;
        }
        LogstashMarker markers = append("domain", session.getDomain())
                .and(append("domain_type",
                        session.getDomainType() == null ? null : session.getDomainType().name()))
                .and(append("snapshot", session.getSnapshotName()));

        LOG.info(markers, "Session started");
    }

    private SessionStepLogRecord buildSessionStepLogRecord(SessionStepLogContext ctx) {
        Exchange exchange = ctx.exchange();
        String sessionElementId = ctx.sessionElementId();
        String nodeId = ctx.nodeId();
        CamelDebuggerProperties dbgProperties = ctx.dbgProperties();
        if (StringUtils.isEmpty(nodeId)) {
            return null;
        }
        String nodeKey = DebuggerUtils.getNodeIdFormatted(nodeId);
        Map<String, String> elementProperties = dbgProperties.getElementProperty(nodeKey);
        if (elementProperties == null) {
            return null;
        }
        String chainElementId = elementProperties.get(ChainProperties.ELEMENT_ID) != null
                ? elementProperties.get(ChainProperties.ELEMENT_ID) : nodeKey;
        String camelElementName = elementProperties.get(ChainProperties.ELEMENT_TYPE);
        String elementName = elementProperties.get(ChainProperties.ELEMENT_NAME);
        String parentElementId = resolveParentElementId(nodeKey, sessionElementId, exchange, dbgProperties);
        ExecutionStatus executionStatus = DebuggerUtils.computeExecutionStatus(exchange, null, camelElementName);
        String elementId = sessionElementId != null ? sessionElementId : nodeKey;
        return new SessionStepLogRecord(
                ctx.domain(), ctx.domainType(), dbgProperties.getDeploymentInfo().getSnapshotName(),
                parentElementId, camelElementName, executionStatus, ctx.bodyForLogging(),
                extractor.convertToJson(ctx.headersForLogging()),
                extractor.convertToJson(ctx.propertiesForLogging()), elementName, elementId, chainElementId);
    }

    private SessionStepLogRecord buildSessionStepLogRecordForStep(SessionStepLogContext ctx) {
        Exchange exchange = ctx.exchange();
        String sessionElementId = ctx.sessionElementId();
        String stepName = ctx.stepName();
        String stepChainElementId = ctx.stepChainElementId();
        CamelDebuggerProperties dbgProperties = ctx.dbgProperties();
        if (StringUtils.isEmpty(stepName)) {
            return null;
        }
        Map<String, String> parentProperties = dbgProperties.getElementProperty(stepChainElementId);
        if (parentProperties == null) {
            return null;
        }
        String chainElementId = parentProperties.get(ChainProperties.ELEMENT_ID) != null
                ? parentProperties.get(ChainProperties.ELEMENT_ID) : stepChainElementId;
        String camelElementName = parentProperties.get(ChainProperties.ELEMENT_TYPE);
        String parentElementId = null;
        Deque<String> steps = exchange.getProperty(Properties.STEPS, Deque.class);
        if (steps != null && steps.size() > 1) {
            parentElementId = steps.stream().skip(1).findFirst().orElse(null);
        }
        ExecutionStatus executionStatus = DebuggerUtils.computeExecutionStatus(exchange, null, camelElementName);
        return new SessionStepLogRecord(
                ctx.domain(), ctx.domainType(), dbgProperties.getDeploymentInfo().getSnapshotName(),
                parentElementId, camelElementName, executionStatus, ctx.bodyForLogging(),
                extractor.convertToJson(ctx.headersForLogging()),
                extractor.convertToJson(ctx.propertiesForLogging()), stepName, sessionElementId, chainElementId);
    }

    @Nullable
    private String resolveParentElementId(String elementId, String sessionElementId, Exchange exchange,
            CamelDebuggerProperties dbgProperties) {
        Map<String, String> elementProperties = dbgProperties.getElementProperty(elementId);
        String explicitParentId = elementProperties != null
                ? elementProperties.get(ChainProperties.PARENT_ELEMENT_ID) : null;
        String rawParentId;
        if (StringUtils.isNotEmpty(explicitParentId)) {
            rawParentId = explicitParentId;
        } else {
            Deque<String> steps = exchange.getProperty(Properties.STEPS, Deque.class);
            rawParentId = (steps != null && !steps.isEmpty()) ? steps.peek() : null;
        }
        if (StringUtils.isEmpty(rawParentId) || StringUtils.equals(rawParentId, sessionElementId)) {
            return null;
        }
        Map<String, String> executionMap = (Map<String, String>) exchange.getProperty(Properties.ELEMENT_EXECUTION_MAP);
        if (executionMap != null) {
            String splitIdChain = exchange.getProperty(Properties.SPLIT_ID_CHAIN, "", String.class);
            String key = DebuggerUtils.getNodeIdForExecutionMap(rawParentId, splitIdChain);
            if (executionMap.containsKey(key)) {
                return executionMap.get(key);
            }
            if (executionMap.containsKey(rawParentId)) {
                return executionMap.get(rawParentId);
            }
        }
        return rawParentId;
    }

    private boolean markJsonLogged(Exchange exchange, String key) {
        if (key == null) {
            return false;
        }
        Set<String> logged = getSessionJsonLoggedSet(exchange);
        return logged.add(key);
    }

    @SuppressWarnings("unchecked")
    private Set<String> getSessionJsonLoggedSet(Exchange exchange) {
        Set<String> local = exchange.getProperty(Properties.SESSION_STEP_JSON_LOGGED, Set.class);
        if (local != null) {
            return local;
        }
        return findLoggedSetInMainExchange(exchange)
                .orElseGet(() -> {
                    Set<String> logged = ConcurrentHashMap.newKeySet();
                    exchange.setProperty(Properties.SESSION_STEP_JSON_LOGGED, logged);
                    return logged;
                });
    }

    @SuppressWarnings("unchecked")
    private Optional<Set<String>> findLoggedSetInMainExchange(Exchange exchange) {
        Map<String, Map<String, Exchange>> exchanges = exchange.getProperty(Properties.EXCHANGES, Map.class);
        if (exchanges == null) {
            return Optional.empty();
        }
        for (Map<String, Exchange> sessionExchanges : exchanges.values()) {
            for (Exchange ex : sessionExchanges.values()) {
                if (Boolean.TRUE.equals(ex.getProperty(CamelConstants.Properties.IS_MAIN_EXCHANGE, Boolean.class))) {
                    return Optional.of(resolveOrCreateLoggedSet(exchange, ex));
                }
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Set<String> resolveOrCreateLoggedSet(Exchange target, Exchange mainExchange) {
        Set<String> logged = mainExchange.getProperty(Properties.SESSION_STEP_JSON_LOGGED, Set.class);
        if (logged != null) {
            target.setProperty(Properties.SESSION_STEP_JSON_LOGGED, logged);
            return logged;
        }
        logged = ConcurrentHashMap.newKeySet();
        mainExchange.setProperty(Properties.SESSION_STEP_JSON_LOGGED, logged);
        target.setProperty(Properties.SESSION_STEP_JSON_LOGGED, logged);
        return logged;
    }
}
