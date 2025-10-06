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
import org.qubership.integration.platform.engine.forms.FormData;
import org.qubership.integration.platform.engine.forms.FormEntry;

import java.util.List;

@ApplicationScoped
@Converter
public class FormDataConverter {
    private static final TypeReference<List<FormEntry>> FORM_ENTRY_LIST_TYPE_REFERENCE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    @Inject
    public FormDataConverter(@Identifier("jsonMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Converter
    public FormData convert(Object value) throws TypeConversionException {
        try {
            List<FormEntry> entries = objectMapper.readValue(String.valueOf(value), FORM_ENTRY_LIST_TYPE_REFERENCE);
            return FormData.builder().entries(entries).build();
        } catch (JsonProcessingException exception) {
            throw new TypeConversionException(value, FormData.class, exception);
        }
    }
}
