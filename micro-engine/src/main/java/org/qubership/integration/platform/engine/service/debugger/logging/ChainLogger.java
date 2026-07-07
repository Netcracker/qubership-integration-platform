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

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.model.logging.LoggedPayloadValues;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;
import org.qubership.integration.platform.engine.service.debugger.tracing.TracingService;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;

@ApplicationScoped
@IfBuildProperty(name = "qip.logging.format", stringValue = "text")
public class ChainLogger extends AbstractChainLogger {
    private static final String LOG_EXCHANGE_FORMAT = "Headers: {}, body: {}, exchange properties: {}";

    public ChainLogger() {
        super(null, null, null, null, null);
    }

    @Inject
    public ChainLogger(TracingService tracingService,
            Instance<OriginatingBusinessIdProvider> originatingBusinessIdProvider,
            PayloadExtractor payloadExtractor,
            ChainRuntimePropertiesService chainRuntimePropertiesService,
            VariablesService variablesService) {
        super(tracingService, originatingBusinessIdProvider, payloadExtractor, chainRuntimePropertiesService,
                variablesService);
    }

    @Override
    public void logExchange(String message, LoggedPayloadValues loggedPayloadValues) {
        chainLogger.info(
                "{} " + LOG_EXCHANGE_FORMAT,
                message,
                loggedPayloadValues.getHeaders(),
                loggedPayloadValues.getBody(),
                loggedPayloadValues.getProperties());
    }

    @Override
    public void logError(String message, Exception exception, LoggedPayloadValues loggedPayloadValues) {
        chainLogger.error(ErrorCode.match(exception),
                "{} {} " + LOG_EXCHANGE_FORMAT,
                message,
                exception.getMessage(),
                loggedPayloadValues.getHeaders(),
                loggedPayloadValues.getBody(),
                loggedPayloadValues.getProperties());
    }

    @Override
    public void logErrorWithHttpParams(String message, ErrorCode errorCode, HttpLogParameters params,
            LoggedPayloadValues loggedPayloadValues) {
        chainLogger.error(errorCode, "{} {} " + LOG_EXCHANGE_FORMAT,
                params.toString(),
                message,
                loggedPayloadValues.getHeaders(),
                loggedPayloadValues.getBody(),
                loggedPayloadValues.getProperties());
    }

    @Override
    public void logHttpParams(String message, HttpLogParameters params, LoggedPayloadValues loggedPayloadValues) {
        logExchange(String.format("%s %s", params.toString(), message), loggedPayloadValues);
    }

    @Override
    public void logFailedHttpOperation(LoggedPayloadValues loggedPayloadValues,
            HttpOperationFailedException httpException, long duration) {
        chainLogger.error(ErrorCode.match(httpException),
                "{} HTTP request failed. " + LOG_EXCHANGE_FORMAT,
                HttpLogParameters.createErrorResponse(httpException, duration).toString(),
                loggedPayloadValues.getHeaders(),
                loggedPayloadValues.getBody(),
                loggedPayloadValues.getProperties());
    }

    @Override
    public void logFailedOperation(LoggedPayloadValues loggedPayloadValues,
            Exception exception, long duration) {
        chainLogger.error(ErrorCode.match(exception),
                "{} HTTP request failed. " + LOG_EXCHANGE_FORMAT,
                HttpLogParameters.createResponse(duration).toString(),
                loggedPayloadValues.getHeaders(),
                loggedPayloadValues.getBody(),
                loggedPayloadValues.getProperties());
    }

    @Override
    public void logExternalServiceParams(String message, HttpLogParameters params, LoggedPayloadValues loggedPayloadValues,
            String externalServiceEnvName,
            String externalServiceAddress) {
        chainLogger.info(
                "{}{} " + LOG_EXCHANGE_FORMAT + ", external service environment name: {}, external service address: {}",
                params == null ? "" : params.toString() + " ",
                message,
                loggedPayloadValues.getHeaders(),
                loggedPayloadValues.getBody(),
                loggedPayloadValues.getProperties(),
                externalServiceEnvName,
                externalServiceAddress);
    }
}
