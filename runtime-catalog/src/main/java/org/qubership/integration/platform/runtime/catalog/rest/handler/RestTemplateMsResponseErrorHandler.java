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

package org.qubership.integration.platform.runtime.catalog.rest.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.qubership.integration.platform.runtime.catalog.exception.ExceptionDTO;
import org.qubership.integration.platform.runtime.catalog.rest.handler.exception.MicroserviceErrorResponseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class RestTemplateMsResponseErrorHandler implements ResponseErrorHandler {

    private final ObjectMapper objectMapper;

    @Autowired
    public RestTemplateMsResponseErrorHandler(@Qualifier("primaryObjectMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    @SuppressWarnings("checkstyle:EmptyCatchBlock")
    public void handleError(ClientHttpResponse response) throws IOException {
        String errorMessage = IOUtils.toString(response.getBody(), StandardCharsets.UTF_8);

        try {
            ExceptionDTO exceptionDTO = objectMapper.readValue(errorMessage, ExceptionDTO.class);
            errorMessage = exceptionDTO.getErrorMessage();
        } catch (Exception ignored) { }

        throw new MicroserviceErrorResponseException(errorMessage, (HttpStatus) response.getStatusCode(), response.getHeaders());
    }
}
