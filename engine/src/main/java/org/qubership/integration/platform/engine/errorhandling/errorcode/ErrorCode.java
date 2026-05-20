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

package org.qubership.integration.platform.engine.errorhandling.errorcode;

import jakarta.annotation.Nullable;
import lombok.Getter;
import lombok.NonNull;
import org.apache.http.HttpStatus;
import org.qubership.integration.platform.engine.camel.components.servlet.exception.ChainGlobalExceptionHandler;
import org.qubership.integration.platform.engine.camel.components.servlet.exception.annotations.ChainExceptionHandler;
import org.qubership.integration.platform.engine.model.errorhandling.ErrorCodePayload;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public enum ErrorCode {

    // Chain runtime errors

    UNEXPECTED_BUSINESS_ERROR("0001", HttpStatus.SC_INTERNAL_SERVER_ERROR, "Chain execution failed due to unexpected business error", "Unexpected Business error"),

    REQUEST_VALIDATION_ERROR("0100", HttpStatus.SC_BAD_REQUEST, "Request has not passed input validations, configured within requested chain", "Chain failed during request validation."),
    REQUESTED_ENDPOINT_NOT_FOUND("0101", HttpStatus.SC_INTERNAL_SERVER_ERROR, "Endpoint specified within a chain not found", "Chain execution failed due to requesting missing endpoint"),
    LOOP_ITERATIONS_LIMIT_REACHED("0102", HttpStatus.SC_INTERNAL_SERVER_ERROR, "Maximum number of iterations reached for 'Loop' element type", "Chain execution failed due to reaching maximum number of iterations"),
    SOCKET_TIMEOUT("0103", HttpStatus.SC_INTERNAL_SERVER_ERROR, "Endpoint specified within a chain timed out", "Chain execution failed due to reaching the timeout for the requested service"),
    SERVICE_RETURNED_ERROR("0104", HttpStatus.SC_INTERNAL_SERVER_ERROR, "Service returned error", "Chain execution failed after receiving the error from service"),
    KAFKA_TIMEOUT("0105", HttpStatus.SC_INTERNAL_SERVER_ERROR, "Unable to reach Kafka broker or topic", "Chain execution failed due to inability to reach Kafka"),

    CHAIN_ENDPOINT_NOT_FOUND("0106", HttpStatus.SC_NOT_FOUND, "Endpoint not found", "Chain request was rejected due to missing endpoint"),
    AUTHORIZATION_ERROR("0107", HttpStatus.SC_FORBIDDEN, "Request has not passed chain's access control settings", "Chain request was rejected due to access denial"),

    METHOD_NOT_ALLOWED("0108", HttpStatus.SC_METHOD_NOT_ALLOWED, "Request method ${httpMethod} is not supported", "Chain request was rejected due to invalid method"),


    SDS_JOB_FINISHED_NOTIFICATION_ERROR("0109", "Unable to notify SDS regarding job instance finished for Job execution id: ${sdsJobExecutionId}", "SDS task execution failed due to inability to pass finish notification"),
    SDS_EXECUTION_INTERNAL_ERROR("0110", "SDS Job ${sdsJobExecutionId} execution failed", "SDS task execution failed due to internal error"),
    SDS_MISSING_JOB_ID("0111", "SDS Job ${sdsJobExecutionId} execution failed. No deployed chains with specified jobId", "SDS task execution failed due to missing jobId"),


    RESPONSE_VALIDATION_ERROR("0112", HttpStatus.SC_INTERNAL_SERVER_ERROR,
            "Response captured by one of the service calls has not passed configured validations",
            "Chain failed due to receiving unexpected response from service"),
    TIMEOUT_REACHED("0113", HttpStatus.SC_INTERNAL_SERVER_ERROR, "Chain timeout reached", "Chain execution failed due to reaching execution timeout"),
    FORCE_TERMINATED("0114", HttpStatus.SC_INTERNAL_SERVER_ERROR, "Chain session was shut down", "Chain execution was stopped manually"),


    // Deployment errors

    PREDEPLOY_CHECK_ERROR("7103", "", "Predeploy check failed"),
    UNEXPECTED_DEPLOYMENT_ERROR("1500", "Error during deployment ${deploymentId} processing", "Unexpected deployment error"),
    DEPLOYMENT_START_ERROR("1501", "Deployment ${deploymentId} was not initialized correctly during pod startup", "Deployment initialization failed");

    @Getter
    private final int httpErrorCode;

    @Getter
    private final ErrorCodePayload payload;

    ErrorCode(String code, @NonNull String message) {
        this.httpErrorCode = 0;
        payload = new ErrorCodePayload(code, message);

    }

    ErrorCode(String code, @NonNull String message, String reason) {
        this.httpErrorCode = 0;
        payload = new ErrorCodePayload(code, message, reason);
    }

    ErrorCode(String code, int httpErrorCode, @NonNull String message) {
        this.httpErrorCode = httpErrorCode;
        payload = new ErrorCodePayload(code, message);

    }

    ErrorCode(String code, int httpErrorCode, @NonNull String message, String reason) {
        this.httpErrorCode = httpErrorCode;
        payload = new ErrorCodePayload(code, message, reason);
    }

    public String getCode() {
        return getPayload().getCode();
    }

    public String getFormattedCode() {
        return String.format("%s-%s", ErrorCodePrefix.getCodePrefix(), getPayload().getCode());
    }

    /***
     * Get ErrorCode by exception matched via @ChainExceptionHandler annotation in ChainGlobalExceptionHandler class
     *
     * @param exception Exception that was thrown
     * @return ErrorCode related to specified exception
     */
    @Nullable
    public static ErrorCode match(@Nullable Throwable exception) {
        return exception == null ? null : ErrorCode.match(exception.getClass());
    }

    /***
     * Get ErrorCode by exception matched via @ChainExceptionHandler annotation in ChainGlobalExceptionHandler class
     *
     * @param clazz Exception class that was thrown
     * @return ErrorCode related to specified exception
     */
    @Nullable
    private static ErrorCode match(Class<? extends Throwable> clazz) {
        Method[] methods = ChainGlobalExceptionHandler.class.getDeclaredMethods();
        ErrorCode defaultErrorCode = null;
        for (Method method : methods) {
            ChainExceptionHandler annotation = method.getAnnotation(ChainExceptionHandler.class);
            if (annotation == null) {
                continue;
            }

            if (annotation.value().length == 0) {
                defaultErrorCode = annotation.errorCode();
                continue;
            }

            if (Arrays.asList(annotation.value()).contains(clazz)) {
                return annotation.errorCode();
            }
        }
        return defaultErrorCode;
    }

    public String compileMessage(String... messageParams) {
        String message = getMessage();
        List<String> extraKeys = getPayload().getExtraKeys();
        if (getMessage() != null && extraKeys.size() == messageParams.length) {
            for (int i = 0; i < extraKeys.size(); i++) {
                String paramKey = extraKeys.get(i);
                message = message.replace("${" + paramKey + "}", messageParams[i]);
            }
        }
        return message;
    }

    public String getMessage() {
        return getPayload().getMessage();
    }
}
