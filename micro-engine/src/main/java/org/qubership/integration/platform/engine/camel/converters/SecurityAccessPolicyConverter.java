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
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.*;
import org.qubership.integration.platform.engine.security.QipSecurityAccessPolicy;

import java.util.List;

@ApplicationScoped
@Converter
public class SecurityAccessPolicyConverter {
    private static final TypeReference<List<String>> STRING_LIST_TYPE_REFERENCE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    @Inject
    public SecurityAccessPolicyConverter(@Identifier("jsonMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Converter
    public QipSecurityAccessPolicy convert(Object value) throws TypeConversionException {
        try {
            List<String> attributes = objectMapper.readValue(String.valueOf(value), STRING_LIST_TYPE_REFERENCE);
            return QipSecurityAccessPolicy.fromStrings(attributes);
        } catch (JsonProcessingException exception) {
            throw new TypeConversionException(value, QipSecurityAccessPolicy.class, exception);
        }
    }
}
