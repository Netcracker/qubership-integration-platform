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

import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.qubership.integration.platform.engine.camel.components.servlet.exception.ChainGlobalExceptionHandler;
import org.qubership.integration.platform.engine.camel.components.servlet.exception.annotations.ChainExceptionHandler;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class ChainExceptionResponseHandlerService {

    private final ChainGlobalExceptionHandler chainGlobalExceptionHandler;

    @Autowired
    public ChainExceptionResponseHandlerService(ChainGlobalExceptionHandler chainGlobalExceptionHandler) {
        this.chainGlobalExceptionHandler = chainGlobalExceptionHandler;
    }

    public void handleExceptionResponse(Exchange exchange, Exception exception) throws InvocationTargetException, IllegalAccessException {
        Method method = getExceptionMethod(exception);
        method.invoke(chainGlobalExceptionHandler, exception, exchange, ErrorCode.match(exception), getAdditionalExtraParams(exchange));
    }

    private Method getExceptionMethod(@NotNull Throwable exception) {
        Method[] methods = ChainGlobalExceptionHandler.class.getDeclaredMethods();
        Method defaultMethod = null;
        for (Method method : methods) {
            ChainExceptionHandler annotation = method.getAnnotation(ChainExceptionHandler.class);
            if (annotation == null) {
                continue;
            }

            if (annotation.value().length == 0) {
                defaultMethod = method;
                continue;
            }

            if (Arrays.stream(annotation.value()).anyMatch(c -> c.equals(exception.getClass()))) {
                return method;
            }
        }
        return defaultMethod;
    }

    private Map<String, String> getAdditionalExtraParams(Exchange exchange) {
        return new HashMap<>(Map.of(
            CamelConstants.ChainProperties.EXCEPTION_EXTRA_SESSION_ID,
                exchange.getProperty(CamelConstants.Properties.SESSION_ID, String.class),
            CamelConstants.ChainProperties.EXCEPTION_EXTRA_FAILED_ELEMENT,
                exchange.getProperty(CamelConstants.ChainProperties.FAILED_ELEMENT_ID, String.class)));
    }
}
