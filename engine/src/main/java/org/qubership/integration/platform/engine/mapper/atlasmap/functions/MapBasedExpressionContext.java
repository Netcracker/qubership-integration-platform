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

package org.qubership.integration.platform.engine.mapper.atlasmap.functions;

import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.ExpressionException;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldGroup;
import org.qubership.integration.platform.mapper.ComplexField;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class MapBasedExpressionContext implements ExpressionContext {
    private final Map<String, Field> variableMap;

    public MapBasedExpressionContext(Map<String, Field> variableMap) {
        this.variableMap = variableMap;
    }

    @Override
    public Field getVariable(String s) throws ExpressionException {
        return variableMap.get(s);
    }

    public static MapBasedExpressionContext fromField(Field field, Function<String, String> pathMapper) {
        return new MapBasedExpressionContext(createFieldMap(field, pathMapper));
    }

    public static Map<String, Field> createFieldMap(Field field, Function<String, String> pathMapper) {
        Map<String, Field> map = new HashMap<>();
        String name = buildVariableName(field, pathMapper);
        map.put(name, field);
        if (field instanceof FieldGroup group) {
            group.getField().stream()
                    .map(f -> createFieldMap(f, pathMapper))
                    .forEach(map::putAll);
        } else if (field instanceof ComplexField complexField) {
            complexField.getChildFields().stream()
                    .map(f -> createFieldMap(f, pathMapper))
                    .forEach(map::putAll);
        }
        return map;
    }

    private static String buildVariableName(Field field, Function<String, String> pathMapper) {
        return field.getDocId() + ":" + pathMapper.apply(field.getPath());
    }
}
