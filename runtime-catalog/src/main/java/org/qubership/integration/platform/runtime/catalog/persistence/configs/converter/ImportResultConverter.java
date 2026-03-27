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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.ImportResult;

import java.io.IOException;

@Converter
public class ImportResultConverter implements AttributeConverter<ImportResult, String> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(ImportResult importResult) {
        try {
            return objectMapper.writeValueAsString(importResult);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("ImportResult object cannot be converted to DB column value", e);
        }
    }

    @Override
    public ImportResult convertToEntityAttribute(String importResultColumnValue) {
        try {
            return objectMapper.readValue(importResultColumnValue, ImportResult.class);
        } catch (IOException e) {
            throw new RuntimeException("Import result value cannot be converted into object: " + importResultColumnValue, e);
        }
    }
}
