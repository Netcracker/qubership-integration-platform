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

package org.qubership.integration.platform.engine.service.debugger.logging;

import org.apache.camel.http.base.HttpOperationFailedException;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.service.debugger.tracing.TracingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(name = "qip.logging.format", havingValue = "text")
public class ChainLogger extends AbstractChainLogger {
    private static final String LOG_EXCHANGE_FORMAT = "Headers: {}, body: {}, exchange properties: {}";

    @Autowired
    public ChainLogger(@Lazy TracingService tracingService,
            Optional<OriginatingBusinessIdProvider> originatingBusinessIdProvider) {
        super(tracingService, originatingBusinessIdProvider);
    }

    @Override
    public void logExchange(String message, String bodyForLogging, Object headersForLogging,
            Object exchangePropertiesForLogging) {
        chainLogger.info(
                "{} " + LOG_EXCHANGE_FORMAT,
                message,
                truncateValue(headersForLogging.toString()),
                truncateValue(bodyForLogging),
                truncateValue(exchangePropertiesForLogging.toString()));
    }

    @Override
    public void logError(String message, Exception exception, String bodyForLogging, Object headersForLogging,
            Object exchangePropertiesForLogging) {
        chainLogger.error(ErrorCode.match(exception),
                "{} {} " + LOG_EXCHANGE_FORMAT,
                message,
                exception.getMessage(),
                headersForLogging,
                bodyForLogging,
                exchangePropertiesForLogging);
    }

    @Override
    public void logErrorWithHttpParams(String message, ErrorCode errorCode, HttpLogParameters params,
            String bodyForLogging, Object headersForLogging, Object exchangePropertiesForLogging) {
        chainLogger.error(errorCode, "{} {} " + LOG_EXCHANGE_FORMAT,
                params.toString(),
                message,
                headersForLogging,
                bodyForLogging,
                exchangePropertiesForLogging);
    }

    @Override
    public void logHttpParams(String message, HttpLogParameters params, String bodyForLogging,
            Object headersForLogging, Object exchangePropertiesForLogging) {
        logExchange(String.format("%s %s", params.toString(), message), bodyForLogging, headersForLogging,
                exchangePropertiesForLogging);
    }

    @Override
    public void logFailedHttpOperation(String bodyForLogging, Object headersForLogging,
            Object exchangePropertiesForLogging, HttpOperationFailedException httpException, long duration) {
        chainLogger.error(ErrorCode.match(httpException),
                "{} HTTP request failed. " + LOG_EXCHANGE_FORMAT,
                HttpLogParameters.createErrorResponse(httpException, duration).toString(),
                headersForLogging,
                bodyForLogging,
                exchangePropertiesForLogging);
    }

    @Override
    public void logFailedOperation(String bodyForLogging, Object headersForLogging, Object exchangePropertiesForLogging,
            Exception exception, long duration) {
        chainLogger.error(ErrorCode.match(exception),
                "{} HTTP request failed. " + LOG_EXCHANGE_FORMAT,
                HttpLogParameters.createResponse(duration).toString(),
                headersForLogging,
                bodyForLogging,
                exchangePropertiesForLogging);
    }

    @Override
    public void logExternalServiceParams(String message, HttpLogParameters params, String bodyForLogging,
            Object headersForLogging,
            Object exchangePropertiesForLogging,
            String externalServiceEnvName,
            String externalServiceAddress) {
        chainLogger.info(
                "{}{} " + LOG_EXCHANGE_FORMAT + ", external service environment name: {}, external service address: {}",
                params == null ? "" : params.toString() + " ",
                message,
                headersForLogging,
                bodyForLogging,
                exchangePropertiesForLogging,
                externalServiceEnvName,
                externalServiceAddress);
    }
}
