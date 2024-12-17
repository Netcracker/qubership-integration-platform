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

package org.qubership.integration.platform.engine.rest.v1.controller;

import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCodeException;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.configuration.camel.CamelServletConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.error.AbstractErrorController;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorViewResolver;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("${server.error.path:${error.path:/error}}")
public class CustomErrorController extends AbstractErrorController {
    @Autowired
    public CustomErrorController(ErrorAttributes errorAttributes, List<ErrorViewResolver> errorViewResolvers) {
        super(errorAttributes, errorViewResolvers);
    }

    @RequestMapping
    public ResponseEntity<?> error(HttpServletRequest request) {
        if (isChainRequest(request)) {
            ErrorCodeException status = getChainErrorStatus(request);
            return new ResponseEntity<>(status.buildResponseObject(),
                    HttpStatus.valueOf(((ErrorCode) status.getErrorCode()).getHttpErrorCode()));
        }

        return getBasicErrorResponse(request);
    }

    private ResponseEntity<Map<String, Object>> getBasicErrorResponse(HttpServletRequest request) {
        HttpStatus status = getStatus(request);
        if (status == HttpStatus.NO_CONTENT) {
            return new ResponseEntity<>(status);
        }
        Map<String, Object> body = getErrorAttributes(request, ErrorAttributeOptions.defaults());
        return new ResponseEntity<>(body, status);
    }

    private ErrorCodeException getChainErrorStatus(HttpServletRequest request) {
        Integer statusCode = (Integer) request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);

        return switch (statusCode) {
            case 405 -> new ErrorCodeException(ErrorCode.METHOD_NOT_ALLOWED, request.getMethod());
            case 404 -> new ErrorCodeException(ErrorCode.CHAIN_ENDPOINT_NOT_FOUND);
            default -> new ErrorCodeException(ErrorCode.UNEXPECTED_BUSINESS_ERROR);
        };
    }

    private boolean isChainRequest(HttpServletRequest request) {
        String requestUri = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return !StringUtils.isBlank(requestUri) && requestUri.startsWith(
            CamelServletConfiguration.CAMEL_ROUTES_PREFIX);
    }
}
