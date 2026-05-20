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

package org.qubership.integration.platform.engine.mapper.atlasmap.json;

import io.atlasmap.json.inspect.JsonInspectionException;
import io.atlasmap.json.inspect.JsonInspectionService;
import io.atlasmap.json.inspect.JsonSchemaInspector;
import io.atlasmap.json.v2.JsonDocument;
import org.apache.commons.lang3.StringUtils;

public class QipJsonInspectionService extends JsonInspectionService {
    private QipJsonInstanceInspector jsonInstanceInspector;

    public QipJsonInspectionService(QipJsonInstanceInspector jsonInstanceInspector) {
        this.jsonInstanceInspector = jsonInstanceInspector;
    }

    @Override
    public JsonDocument inspectJsonDocument(String sourceDocument) throws JsonInspectionException {
        return StringUtils.isBlank(sourceDocument) ? new JsonDocument() : doInspectJsonDocument(sourceDocument);
    }

    @Override
    public JsonDocument inspectJsonSchema(String jsonSchema) throws JsonInspectionException {
        return StringUtils.isBlank(jsonSchema) ? new JsonDocument() : doInspectJsonSchema(jsonSchema);
    }

    protected JsonDocument doInspectJsonDocument(String sourceDocument) throws JsonInspectionException {
        if (sourceDocument == null || sourceDocument.isEmpty() || sourceDocument.trim().isEmpty()) {
            throw new IllegalArgumentException("Source document cannot be null, empty or contain only whitespace.");
        }
        String cleanDocument = cleanJsonDocument(sourceDocument);

        if (cleanDocument.startsWith("{") || cleanDocument.startsWith("[")) {
            return jsonInstanceInspector.inspect(cleanDocument);
        }
        throw new JsonInspectionException("JSON data must begin with either '{' or '['");
    }

    protected JsonDocument doInspectJsonSchema(String jsonSchema) throws JsonInspectionException {
        if (jsonSchema == null || jsonSchema.isEmpty() || jsonSchema.trim().isEmpty()) {
            throw new IllegalArgumentException("Schema cannot be null, empty or contain only whitespace.");
        }
        String cleanDocument = cleanJsonDocument(jsonSchema);

        if (cleanDocument.startsWith("{") || cleanDocument.startsWith("[")) {
            return JsonSchemaInspector.instance().inspect(cleanDocument);
        }
        throw new JsonInspectionException("JSON schema must begin with either '{' or '['");
    }
}
