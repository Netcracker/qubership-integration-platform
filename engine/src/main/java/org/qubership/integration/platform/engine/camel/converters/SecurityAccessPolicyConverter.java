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

package org.qubership.integration.platform.engine.camel.converters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.engine.security.QipSecurityAccessPolicy;
import org.apache.camel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SecurityAccessPolicyConverter implements TypeConverter {
    private static final TypeReference<List<String>> stringListTypeReference = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    @Autowired
    public SecurityAccessPolicyConverter(@Qualifier("jsonMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean allowNull() {
        return false;
    }

    @Override
    public <T> T convertTo(Class<T> type, Object value) throws TypeConversionException {
        try {
            List<String> attributes = objectMapper.readValue(value.toString(), stringListTypeReference);
            return (T) QipSecurityAccessPolicy.fromStrings(attributes);
        } catch (JsonProcessingException exception) {
            throw new TypeConversionException(value, type, exception);
        }
    }

    @Override
    public <T> T convertTo(Class<T> type, Exchange exchange, Object value) throws TypeConversionException {
        return convertTo(type, value);
    }

    @Override
    public <T> T mandatoryConvertTo(Class<T> type, Object value)
            throws TypeConversionException, NoTypeConversionAvailableException {
        return convertTo(type, value);
    }

    @Override
    public <T> T mandatoryConvertTo(Class<T> type, Exchange exchange, Object value)
            throws TypeConversionException, NoTypeConversionAvailableException {
        return convertTo(type, value);
    }

    @Override
    public <T> T tryConvertTo(Class<T> type, Object value) {
        try {
            return convertTo(type, value);
        } catch (TypeConversionException ignored) {
            return null;
        }
    }

    @Override
    public <T> T tryConvertTo(Class<T> type, Exchange exchange, Object value) {
        try {
            return convertTo(type, exchange, value);
        } catch (TypeConversionException ignored) {
            return null;
        }
    }
}
