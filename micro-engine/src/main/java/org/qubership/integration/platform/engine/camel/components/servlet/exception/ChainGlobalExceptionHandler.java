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

package org.qubership.integration.platform.engine.camel.components.servlet.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.CamelAuthorizationException;
import org.apache.camel.Exchange;
import org.apache.camel.component.http.HttpConstants;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.kafka.common.errors.TimeoutException;
import org.qubership.integration.platform.engine.camel.components.directvm.ChainConsumerNotAvailableException;
import org.qubership.integration.platform.engine.camel.components.servlet.exception.annotations.ChainExceptionHandler;
import org.qubership.integration.platform.engine.camel.exceptions.IterationLimitException;
import org.qubership.integration.platform.engine.errorhandling.ChainExecutionTerminatedException;
import org.qubership.integration.platform.engine.errorhandling.ChainExecutionTimeoutException;
import org.qubership.integration.platform.engine.errorhandling.ResponseValidationException;
import org.qubership.integration.platform.engine.errorhandling.ValidationException;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCodeException;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;

@Component
public class ChainGlobalExceptionHandler {

    private final ObjectMapper jsonMapper;

    @Autowired
    public ChainGlobalExceptionHandler(@Qualifier("jsonMapper") ObjectMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }


    // Default handler
    @ChainExceptionHandler(errorCode = ErrorCode.UNEXPECTED_BUSINESS_ERROR)
    public void handleGeneralException(Throwable exception, Exchange exchange, ErrorCode errorCode, Map<String, String> extraParameters) throws IOException {
        makeExceptionResponseInExchange(exchange, errorCode, extraParameters);
    }


    // Custom handlers
    @ChainExceptionHandler(value = ValidationException.class, errorCode = ErrorCode.REQUEST_VALIDATION_ERROR)
    public void handleException(ValidationException exception, Exchange exchange, ErrorCode errorCode, Map<String, String> extraParameters) throws IOException {
        extraParameters.put(CamelConstants.ChainProperties.EXCEPTION_EXTRA_VALIDATION_RESULT, exception.getMessage());
        makeExceptionResponseInExchange(exchange, errorCode, extraParameters);
    }

    @ChainExceptionHandler(value = ResponseValidationException.class, errorCode = ErrorCode.RESPONSE_VALIDATION_ERROR)
    public void handleException(ResponseValidationException exception, Exchange exchange, ErrorCode errorCode, Map<String, String> extraParameters) throws IOException {
        extraParameters.put(CamelConstants.ChainProperties.EXCEPTION_EXTRA_VALIDATION_RESULT, exception.getMessage());
        makeExceptionResponseInExchange(exchange, errorCode, extraParameters);
    }

    @ChainExceptionHandler(value = CamelAuthorizationException.class, errorCode = ErrorCode.AUTHORIZATION_ERROR)
    public void handleException(CamelAuthorizationException exception, Exchange exchange, ErrorCode errorCode, Map<String, String> extraParameters) throws IOException {
        makeExceptionResponseInExchange(exchange, errorCode, extraParameters);
    }

    @ChainExceptionHandler(value = ChainConsumerNotAvailableException.class, errorCode = ErrorCode.CHAIN_ENDPOINT_NOT_FOUND)
    public void handleChainCallException(ChainConsumerNotAvailableException exception, Exchange exchange, ErrorCode errorCode, Map<String, String> extraParameters) throws IOException {
        makeExceptionResponseInExchange(exchange, errorCode, extraParameters);
    }

    @ChainExceptionHandler(value = UnknownHostException.class, errorCode = ErrorCode.REQUESTED_ENDPOINT_NOT_FOUND)
    public void handleException(UnknownHostException exception, Exchange exchange, ErrorCode errorCode, Map<String, String> extraParameters) throws IOException {
        makeExceptionResponseInExchange(exchange, errorCode, extraParameters);
    }

    @ChainExceptionHandler(value = HttpOperationFailedException.class, errorCode = ErrorCode.SERVICE_RETURNED_ERROR)
    public void handleException(HttpOperationFailedException exception, Exchange exchange, ErrorCode errorCode, Map<String, String> extraParameters) throws IOException {
        if (exception.getHttpResponseCode() == 504) {
            // redirect to timeout handler
            handleTimeoutException(exception, exchange, ErrorCode.SOCKET_TIMEOUT, extraParameters);
            return;
        }
        makeExceptionResponseInExchange(exchange, errorCode, extraParameters);
    }

    @ChainExceptionHandler(value = SocketTimeoutException.class, errorCode = ErrorCode.SOCKET_TIMEOUT)
    public void handleTimeoutException(Exception exception, Exchange exchange, ErrorCode errorCode, Map<String, String> extraParameters) throws IOException {
        makeExceptionResponseInExchange(exchange, errorCode, extraParameters);
    }

    @ChainExceptionHandler(value = TimeoutException.class, errorCode = ErrorCode.KAFKA_TIMEOUT)
    public void handleException(TimeoutException exception, Exchange exchange, ErrorCode errorCode, Map<String, String> extraParameters) throws IOException {
        makeExceptionResponseInExchange(exchange, errorCode, extraParameters);
    }

    @ChainExceptionHandler(value = IterationLimitException.class, errorCode = ErrorCode.LOOP_ITERATIONS_LIMIT_REACHED)
    public void handleException(IterationLimitException exception, Exchange exchange, ErrorCode errorCode, Map<String, String> extraParameters) throws IOException {
        makeExceptionResponseInExchange(exchange, errorCode, extraParameters);
    }

    @ChainExceptionHandler(value = ChainExecutionTimeoutException.class, errorCode = ErrorCode.TIMEOUT_REACHED)
    public void handleException(ChainExecutionTimeoutException exception, Exchange exchange, ErrorCode errorCode, Map<String, String> extraParameters) throws IOException {
        makeExceptionResponseInExchange(exchange, errorCode, extraParameters);
    }

    @ChainExceptionHandler(value = ChainExecutionTerminatedException.class, errorCode = ErrorCode.FORCE_TERMINATED)
    public void handleException(ChainExecutionTerminatedException exception, Exchange exchange, ErrorCode errorCode, Map<String, String> extraParameters) throws IOException {
        makeExceptionResponseInExchange(exchange, errorCode, extraParameters);
    }

    private void makeExceptionResponseInExchange(Exchange exchange, ErrorCode errorCode, Map<String, String> extraParameters) throws IOException {
        exchange.getMessage().removeHeaders("*");
        exchange.getMessage().setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        exchange.getMessage().setHeader(HttpConstants.HTTP_RESPONSE_CODE, errorCode.getHttpErrorCode());
        ErrorCodeException codeException = new ErrorCodeException(errorCode, extraParameters);
        exchange.getMessage().setBody(jsonMapper.writeValueAsString(codeException.buildResponseObject()));
    }
}
