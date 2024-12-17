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

import org.qubership.integration.platform.engine.mapper.atlasmap.FieldUtils;
import org.qubership.integration.platform.mapper.ComplexField;
import io.atlasmap.core.AtlasPath;
import io.atlasmap.core.BaseFunctionFactory;
import io.atlasmap.expression.Expression;
import io.atlasmap.expression.parser.ParseException;
import io.atlasmap.v2.*;

import java.util.ArrayList;
import java.util.List;

import static org.qubership.integration.platform.engine.mapper.atlasmap.FieldUtils.replacePathSegments;
import static java.util.Objects.isNull;

public class MergeObjectsFunctionFactory extends BaseFunctionFactory {
    @Override
    public String getName() {
        return "mergeObjects";
    }

    @Override
    public Expression create(List<Expression> args) throws ParseException {
        return ctx -> {
            List<Field> fields = new ArrayList<>();
            for (Expression expression : args) {
                if (isNull(expression)) {
                    continue;
                }
                Field field = expression.evaluate(ctx);
                if (isNull(field)) {
                    continue;
                }
                Field f = FieldUtils.cloneField(field);
                replacePathSegments(
                        f,
                        new AtlasPath(f.getPath()).getSegments(true),
                        new AtlasPath("/result").getSegments(true)
                );
                if (f instanceof FieldGroup group) {
                    group.getField().stream().filter(ComplexField.class::isInstance).forEach(fields::add);
                } else if (f instanceof ComplexField complexField) {
                    fields.addAll(complexField.getChildFields());
                }
            }
            ComplexField result = new ComplexField(fields);
            result.setFieldType(FieldType.COMPLEX);
            result.setCollectionType(CollectionType.NONE);
            result.setPath("/result");
            result.setName("result");
            return result;
        };
    }
}
