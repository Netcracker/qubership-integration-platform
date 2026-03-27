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

package org.qubership.integration.platform.engine.camel.processors;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.language.simple.SimpleLanguage;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.model.constants.BusinessIds;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.logging.LogLoggingLevel;
import org.qubership.integration.platform.engine.service.debugger.logging.ChainLogger;
import org.qubership.integration.platform.engine.util.MDCUtil;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class LogRecordProcessor implements Processor {
    enum LogLevel {
        ERROR,
        WARNING,
        INFO
    }

    private static final String LOG_RECORD_PROPERTY_PREFIX = CamelConstants.INTERNAL_PROPERTY_PREFIX + "logRecord_";
    private static final String PROPERTY_LOG_LEVEL = LOG_RECORD_PROPERTY_PREFIX + "logLevel";
    private static final String PROPERTY_SENDER = LOG_RECORD_PROPERTY_PREFIX + "sender";
    private static final String PROPERTY_RECEIVER = LOG_RECORD_PROPERTY_PREFIX + "receiver";
    private static final String PROPERTY_BUSINESS_IDENTIFIERS = LOG_RECORD_PROPERTY_PREFIX + "businessIdentifiers";
    private static final String PROPERTY_MESSAGE = LOG_RECORD_PROPERTY_PREFIX + "message";

    private final ChainLogger chainLogger;

    private final SimpleLanguage simpleInterpreter;

    @Autowired
    public LogRecordProcessor(ChainLogger chainLogger, SimpleLanguage simpleInterpreter) {
        this.chainLogger = chainLogger;
        this.simpleInterpreter = simpleInterpreter;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        createLogRecord(exchange);
    }

    private void createLogRecord(Exchange exchange) {
        LogLevel logLevel = Optional.of(exchange.getProperty(PROPERTY_LOG_LEVEL, String.class))
                .map(value -> Enum.valueOf(LogLevel.class, value.toUpperCase()))
                .orElse(null);
        String sender = exchange.getProperty(PROPERTY_SENDER, String.class);
        String receiver = exchange.getProperty(PROPERTY_RECEIVER, String.class);
        String message = exchange.getProperty(PROPERTY_MESSAGE, String.class);

        Map<Object, Object> businessIdentifiers = new HashMap<>();

        if (StringUtils.isNotBlank((String) exchange.getProperty(PROPERTY_BUSINESS_IDENTIFIERS))) {
            businessIdentifiers = exchange.getProperty(PROPERTY_BUSINESS_IDENTIFIERS, Map.class);
        }

        businessIdentifiers.replaceAll((k, v) -> evaluateSimpleExpression(exchange, v.toString()));

        String logRecordMessage = constructLogRecordMessage(sender, receiver, message);

        if (!businessIdentifiers.isEmpty()) {
            MDCUtil.setBusinessIds(businessIdentifiers);
        }

        LogLoggingLevel globalLogLevel = LogLoggingLevel.defaultLevel();
        switch (logLevel) {
            case ERROR -> chainLogger.error(logRecordMessage);
            case WARNING -> {
                if (globalLogLevel.isWarnLevel()) {
                    chainLogger.warn(logRecordMessage);
                }
            }
            case INFO -> {
                if (globalLogLevel.isInfoLevel()) {
                    chainLogger.info(logRecordMessage);
                }
            }
        }

        MDC.remove(BusinessIds.BUSINESS_IDS);
    }

    private String constructLogRecordMessage(String sender, String receiver, String message) {
        String recordMessage = "";

        if (!StringUtils.isEmpty(sender)) {
            recordMessage = String.format("[sender=%-16s]", sender);
        }
        if (!StringUtils.isEmpty(receiver)) {
            recordMessage = recordMessage + setDelimiterIfNeeded(recordMessage) + String.format("[receiver=%-16s]", receiver);
        }

        return recordMessage + setDelimiterIfNeeded(recordMessage) + message;
    }

    private String evaluateSimpleExpression(Exchange exchange, String str) {
        return simpleInterpreter.createExpression(str).evaluate(exchange, String.class);
    }

    private String setDelimiterIfNeeded(String text) {
        return (!StringUtils.isEmpty(text) ? " " : "");
    }
}
