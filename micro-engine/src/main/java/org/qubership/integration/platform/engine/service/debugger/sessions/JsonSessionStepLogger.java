package org.qubership.integration.platform.engine.service.debugger.sessions;

import io.quarkiverse.loggingjson.providers.StructuredArgument;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.ElementInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.model.Session;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.logging.SessionLogDetails;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;
import org.qubership.integration.platform.engine.service.debugger.logging.AbstractTruncatedFieldLogger;
import org.qubership.integration.platform.engine.service.debugger.logging.LogExchangeMarkers;
import org.qubership.integration.platform.engine.service.debugger.util.DebuggerUtils;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.quarkiverse.loggingjson.providers.KeyValueStructuredArgument.kv;

@ApplicationScoped
@IfBuildProperty(name = "qip.logging.format", stringValue = "json", enableIfMissing = true)
public class JsonSessionStepLogger extends AbstractTruncatedFieldLogger implements SessionStepLogger {

    private static final Logger LOG = LoggerFactory.getLogger(JsonSessionStepLogger.class);
    private final PayloadExtractor extractor;
    private final ChainRuntimePropertiesService chainRuntimePropertiesService;
    private final LogExchangeMarkers logExchangeMarkers;

    @Inject
    public JsonSessionStepLogger(PayloadExtractor extractor,
            ChainRuntimePropertiesService chainRuntimePropertiesService,
            LogExchangeMarkers logExchangeMarkers) {
        this.extractor = extractor;
        this.chainRuntimePropertiesService = chainRuntimePropertiesService;
        this.logExchangeMarkers = logExchangeMarkers;
    }

    @Override
    public void recordStepAfter(SessionStepLogContext ctx) {
        ChainRuntimeProperties props = chainRuntimePropertiesService.getRuntimeProperties(ctx.exchange());
        SessionLogDetails details = props.getSessionLogDetails();
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
        ChainRuntimeProperties props = chainRuntimePropertiesService.getRuntimeProperties(ctx.exchange());
        SessionLogDetails details = props.getSessionLogDetails();
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

        List<StructuredArgument> args = new ArrayList<>();
        args.add(kv("domain", logRecord.domain()));
        args.add(kv("domain_type", logRecord.domainType() == null ? null : logRecord.domainType().name()));
        args.add(kv("snapshot", logRecord.snapshotName()));
        args.add(kv("element_id", logRecord.elementId()));
        args.add(kv("parent_element_id", logRecord.parentElementId()));
        args.add(kv("element_name", logRecord.elementName()));
        args.addAll(logExchangeMarkers.buildExchangeMarkers(
                logRecord.bodyAfter(), logRecord.headersAfter(), logRecord.propertiesAfter()));

        if (LOG.isInfoEnabled()) {
            LOG.info(buildMessage(logRecord), args.toArray());
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
        List<StructuredArgument> args = new ArrayList<>();
        args.add(kv("domain", session.getDomain()));
        args.add(kv("domain_type", session.getDomainType() == null ? null : session.getDomainType().name()));
        args.add(kv("snapshot", session.getSnapshotName()));
        LOG.info("Session started", args.toArray());
    }

    private SessionStepLogRecord buildSessionStepLogRecord(SessionStepLogContext ctx) {
        Exchange exchange = ctx.exchange();
        String sessionElementId = ctx.sessionElementId();
        String nodeId = ctx.nodeId();
        if (StringUtils.isEmpty(nodeId)) {
            return null;
        }
        String nodeKey = DebuggerUtils.getNodeIdFormatted(nodeId);
        Optional<ElementInfo> opt = MetadataUtil.lookupBeanForElement(exchange, nodeKey, ElementInfo.class);
        if (opt.isEmpty()) {
            return null;
        }
        ElementInfo elementInfo = opt.get();
        String chainElementId = elementInfo.getId() != null ? elementInfo.getId() : nodeKey;
        String camelElementName = elementInfo.getType();
        String elementName = elementInfo.getName();
        String parentElementId = resolveParentElementId(nodeKey, sessionElementId, exchange);
        ExecutionStatus executionStatus = DebuggerUtils.computeExecutionStatus(exchange, null, camelElementName);
        String snapshotName = MetadataUtil.getBean(exchange, DeploymentInfo.class).getSnapshot().getName();
        String elementId = sessionElementId != null ? sessionElementId : nodeKey;
        return new SessionStepLogRecord(
                ctx.domain(), ctx.domainType(), snapshotName,
                parentElementId, camelElementName, executionStatus, ctx.bodyForLogging(),
                extractor.convertToJson(ctx.headersForLogging()),
                extractor.convertToJson(ctx.propertiesForLogging()), elementName, elementId, chainElementId);
    }

    private SessionStepLogRecord buildSessionStepLogRecordForStep(SessionStepLogContext ctx) {
        Exchange exchange = ctx.exchange();
        String sessionElementId = ctx.sessionElementId();
        String stepName = ctx.stepName();
        String stepChainElementId = ctx.stepChainElementId();
        if (StringUtils.isEmpty(stepName)) {
            return null;
        }
        Optional<ElementInfo> opt = MetadataUtil.lookupBeanForElement(exchange, stepChainElementId, ElementInfo.class);
        if (opt.isEmpty()) {
            return null;
        }
        ElementInfo elementInfo = opt.get();
        String chainElementId = elementInfo.getId() != null ? elementInfo.getId() : stepChainElementId;
        String camelElementName = elementInfo.getType();
        String parentElementId = null;
        Deque<String> steps = exchange.getProperty(CamelConstants.Properties.STEPS, Deque.class);
        if (steps != null && steps.size() > 1) {
            parentElementId = steps.stream().skip(1).findFirst().orElse(null);
        }
        ExecutionStatus executionStatus = DebuggerUtils.computeExecutionStatus(exchange, null, camelElementName);
        String snapshotName = MetadataUtil.getBean(exchange, DeploymentInfo.class).getSnapshot().getName();
        return new SessionStepLogRecord(
                ctx.domain(), ctx.domainType(), snapshotName,
                parentElementId, camelElementName, executionStatus, ctx.bodyForLogging(),
                extractor.convertToJson(ctx.headersForLogging()),
                extractor.convertToJson(ctx.propertiesForLogging()), stepName, sessionElementId, chainElementId);
    }

    private String resolveParentElementId(String elementId, String sessionElementId, Exchange exchange) {
        Optional<ElementInfo> opt = MetadataUtil.lookupBeanForElement(exchange, elementId, ElementInfo.class);
        String explicitParentId = opt.map(ElementInfo::getParentId).orElse(null);
        String rawParentId;
        if (StringUtils.isNotEmpty(explicitParentId)) {
            rawParentId = explicitParentId;
        } else {
            Deque<String> steps = exchange.getProperty(CamelConstants.Properties.STEPS, Deque.class);
            rawParentId = (steps != null && !steps.isEmpty()) ? steps.peek() : null;
        }
        if (StringUtils.isEmpty(rawParentId) || StringUtils.equals(rawParentId, sessionElementId)) {
            return null;
        }
        Map<String, String> executionMap = (Map<String, String>) exchange.getProperty(CamelConstants.Properties.ELEMENT_EXECUTION_MAP);
        if (executionMap != null) {
            String splitIdChain = exchange.getProperty(CamelConstants.Properties.SPLIT_ID_CHAIN, "", String.class);
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
        Set<String> local = exchange.getProperty(CamelConstants.Properties.SESSION_STEP_JSON_LOGGED, Set.class);
        if (local != null) {
            return local;
        }
        return findLoggedSetInMainExchange(exchange)
                .orElseGet(() -> {
                    Set<String> logged = ConcurrentHashMap.newKeySet();
                    exchange.setProperty(CamelConstants.Properties.SESSION_STEP_JSON_LOGGED, logged);
                    return logged;
                });
    }

    @SuppressWarnings("unchecked")
    private Optional<Set<String>> findLoggedSetInMainExchange(Exchange exchange) {
        Map<String, Map<String, Exchange>> exchanges = exchange.getProperty(CamelConstants.Properties.EXCHANGES, Map.class);
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
        Set<String> logged = mainExchange.getProperty(CamelConstants.Properties.SESSION_STEP_JSON_LOGGED, Set.class);
        if (logged != null) {
            target.setProperty(CamelConstants.Properties.SESSION_STEP_JSON_LOGGED, logged);
            return logged;
        }
        logged = ConcurrentHashMap.newKeySet();
        mainExchange.setProperty(CamelConstants.Properties.SESSION_STEP_JSON_LOGGED, logged);
        target.setProperty(CamelConstants.Properties.SESSION_STEP_JSON_LOGGED, logged);
        return logged;
    }
}
