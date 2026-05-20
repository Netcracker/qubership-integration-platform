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

package org.qubership.integration.platform.runtime.catalog.mapper.atlasmap;

import io.atlasmap.v2.FieldType;
import org.qubership.integration.platform.runtime.catalog.mapper.DataTypeUtils;
import org.qubership.integration.platform.runtime.catalog.model.mapper.datatypes.ArrayType;
import org.qubership.integration.platform.runtime.catalog.model.mapper.datatypes.DataType;
import org.qubership.integration.platform.runtime.catalog.model.mapper.datatypes.TypeDefinition;

import java.util.Collections;
import java.util.Map;

public class DataTypeToFieldTypeConverter {
    public FieldType convert(DataType type) {
        return convert(type, Collections.emptyMap());
    }

    public FieldType convert(DataType type, Map<String, TypeDefinition> definitionMap) {
        var result = DataTypeUtils.resolveType(type, definitionMap);
        return switch (result.type().getKind()) {
            case INTEGER -> FieldType.NUMBER;
            case BOOLEAN -> FieldType.BOOLEAN;
            case STRING -> FieldType.STRING;
            case OBJECT -> FieldType.COMPLEX;
            case ARRAY -> convert(((ArrayType) result.type()).getItemType(), result.definitionMap());
            case NULL, REFERENCE, ALL_OF, ANY_OF, ONE_OF -> FieldType.ANY;
        };
    }
}
