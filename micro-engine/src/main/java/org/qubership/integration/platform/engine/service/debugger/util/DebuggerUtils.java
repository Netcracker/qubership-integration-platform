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

package org.qubership.integration.platform.engine.service.debugger.util;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.util.ExchangeUtil;

import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class DebuggerUtils {

    private static final String ELEMENT_STEP_PREFIX = "--";

    public static boolean isFailedOperation(Exchange exchange) {
        return exchange.getException() != null;
    }

    public static String getNodeIdFormatted(String nodeId) {
        return CamelConstants.CUSTOM_STEP_ID_PATTERN.matcher(nodeId).matches()
                ? CamelConstants.NAME_STEP_REG_EXP_PATTERN.matcher(nodeId).replaceAll("")
                : nodeId;
    }

    public static String getStepChainElementId(String fullStepId) {
        int index = fullStepId.indexOf(ELEMENT_STEP_PREFIX);
        return index < 0 ? fullStepId : fullStepId.substring(index + ELEMENT_STEP_PREFIX.length());
    }

    public static String getStepNameFormatted(String nodeId) {
        return CamelConstants.CUSTOM_STEP_ID_PATTERN.matcher(nodeId).matches()
                ? CamelConstants.UUID_STEP_REG_EXP_PATTERN.matcher(nodeId).replaceAll("")
                : nodeId;
    }

    public static ExecutionStatus extractExecutionStatus(Exchange exchange) {
        AtomicBoolean overallStatusWarn = exchange.getProperty(Properties.OVERALL_STATUS_WARNING, AtomicBoolean.class);
        boolean isWarn = Boolean.TRUE.equals(exchange.getProperty(Properties.ELEMENT_WARNING, Boolean.class));
        if (overallStatusWarn != null) {
            isWarn = isWarn || overallStatusWarn.get();
        }

        if (isWarn) {
            return ExecutionStatus.COMPLETED_WITH_WARNINGS;
        }

        Throwable caughtException = exchange.getProperty(ExchangePropertyKey.EXCEPTION_CAUGHT, Throwable.class);
        Throwable lastException = exchange.getProperty(CamelConstants.Properties.LAST_EXCEPTION, Throwable.class);
        Boolean sessionFailed = exchange.getProperty(CamelConstants.Properties.SESSION_FAILED, Boolean.FALSE, Boolean.class);
        if (caughtException != null || sessionFailed || lastException != null) {
            return ExecutionStatus.COMPLETED_WITH_ERRORS;
        }

        return ExecutionStatus.COMPLETED_NORMALLY;
    }

    public static void removeStepPropertyFromAllExchanges(Exchange exchange,
        String sessionElementId) {
        String sessionId = ExchangeUtil.getSessionId(exchange);
        ConcurrentMap<String, Exchange> exchanges = (ConcurrentMap<String, Exchange>) exchange.getProperty(
            Properties.EXCHANGES, ConcurrentMap.class).get(sessionId);
        if (exchanges != null) {
            exchanges.forEach((id, value) -> value.getProperty(Properties.STEPS, Deque.class)
                    .removeIf(step -> step.equals(sessionElementId)));
        }
    }

    public static String getNodeIdForExecutionMap(String nodeId, String splitId) {
        return splitId == null ? nodeId : (nodeId + splitId);
    }

    public static Throwable getExceptionFromExchange(Exchange exchange) {
        Throwable exception = exchange.getProperty(ExchangePropertyKey.EXCEPTION_CAUGHT, Throwable.class);
        if (exception == null) {
            exception = exchange.getProperty(CamelConstants.Properties.LAST_EXCEPTION, Throwable.class);
        }
        if (exception == null) {
            exception = exchange.getException();
        }
        return exception;
    }

    public static void setOverallWarning(Exchange exchange, boolean value) {
        AtomicBoolean overallStatusWarn =
            exchange.getProperty(Properties.OVERALL_STATUS_WARNING, AtomicBoolean.class);
        if (overallStatusWarn != null) {
            overallStatusWarn.set(value);
        }
    }
}
