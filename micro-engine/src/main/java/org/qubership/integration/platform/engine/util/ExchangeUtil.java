package org.qubership.integration.platform.engine.util;

import org.apache.camel.Exchange;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.service.ExecutionStatus;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.IS_MAIN_EXCHANGE;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.SYSTEM_PROPERTY_PREFIX;

public class ExchangeUtil {
    private ExchangeUtil() {
    }

    public static String getSessionId(Exchange exchange) {
        return exchange.getProperty(CamelConstants.Properties.SESSION_ID, String.class);
    }

    public static void setSessionProperties(
            Exchange exchange,
            String sessionId,
            String started,
            Long startedMillis,
            boolean shouldBeLogged
    ) {
        exchange.setProperty(CamelConstants.Properties.SESSION_ID, sessionId);
        exchange.setProperty(CamelConstants.Properties.SESSION_SHOULD_BE_LOGGED, shouldBeLogged);
        exchange.setProperty(IS_MAIN_EXCHANGE, true);
        exchange.setProperty(CamelConstants.Properties.START_TIME, started);
        exchange.setProperty(CamelConstants.Properties.START_TIME_MS, startedMillis);
        exchange.getProperty(CamelConstants.Properties.EXCHANGES, ConcurrentHashMap.class)
                .put(sessionId, new ConcurrentHashMap<String, Exchange>());
    }

    public static Long getSessionStartTime(Exchange exchange) {
        return exchange.getProperty(CamelConstants.Properties.START_TIME_MS, Long.class);
    }

    public static Long getSessionDuration(Exchange exchange) {
        Long sessionStartTime = getSessionStartTime(exchange);
        return sessionStartTime == null ? null : System.currentTimeMillis() - sessionStartTime;
    }

    public static Long getExchangeDuration(Exchange exchange) {
        Long exchangeStartTime = exchange.getProperty(CamelConstants.Properties.EXCHANGE_START_TIME_MS, Long.class);
        return exchangeStartTime == null ? null : System.currentTimeMillis() - exchangeStartTime;
    }

    public static void initInternalProperties(Exchange exchange) {
        exchange.setProperty(CamelConstants.Properties.STEPS,
                exchange.getProperty(CamelConstants.Properties.STEPS) == null
                        ? new ConcurrentLinkedDeque<>()
                        : new ConcurrentLinkedDeque<>(exchange.getProperty(CamelConstants.Properties.STEPS, ConcurrentLinkedDeque.class)));

        exchange.setProperty(CamelConstants.Properties.EXCHANGES,
                exchange.getProperty(CamelConstants.Properties.EXCHANGES) == null
                        ? new ConcurrentHashMap<>()
                        : new ConcurrentHashMap<>(exchange.getProperty(CamelConstants.Properties.EXCHANGES, ConcurrentHashMap.class)));

        exchange.setProperty(CamelConstants.Properties.EXCHANGE_START_TIME_MS, System.currentTimeMillis());

        // Duplicating traceMe header value to the corresponding property.
        exchange.setProperty(CamelConstants.Properties.TRACE_ME,
                Boolean.valueOf(exchange.getMessage().getHeader(
                        CamelConstants.Headers.TRACE_ME, "", String.class)));
    }

    public static void putToExchangeMap(String sessionId, Exchange exchange) {
        Map<String, Exchange> exchanges = getSessionExchangeMap(sessionId, exchange);
        if (exchanges != null) {
            exchanges.put(exchange.getExchangeId(), exchange);
        }
    }

    public static void removeFromExchangeMap(String sessionId, Exchange exchange) {
        Map<String, Exchange> exchanges = getSessionExchangeMap(sessionId, exchange);
        if (exchanges != null) {
            exchanges.remove(exchange.getExchangeId());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Exchange> getSessionExchangeMap(String sessionId, Exchange exchange) {
        Map<String, Map<String, Exchange>> exchangesBySessionId = exchange.getProperty(
                CamelConstants.Properties.EXCHANGES, Map.class);
        return exchangesBySessionId == null ? null : exchangesBySessionId.get(sessionId);
    }

    public static ExecutionStatus getEffectiveExecutionStatus(Exchange exchange, ExecutionStatus status) {
        String propertyName = SYSTEM_PROPERTY_PREFIX + "executionStatus"; // TODO move to CamelConstants
        return Optional.ofNullable(exchange.getProperty(propertyName, ExecutionStatus.class))
                .map(executionStatus -> ExecutionStatus.computeHigherPriorityStatus(executionStatus, status))
                .orElse(status);
    }
}
