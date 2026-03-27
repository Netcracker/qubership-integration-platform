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

import io.atlasmap.core.BaseFunctionFactory;
import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.parser.ParseException;
import io.atlasmap.v2.AtlasModelFactory;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldGroup;
import io.atlasmap.v2.FieldType;

import java.util.List;

import static org.qubership.integration.platform.engine.mapper.atlasmap.FieldUtils.*;

public class MapFunctionFactory extends BaseFunctionFactory {
    @Override
    public String getName() {
        return "map";
    }

    @Override
    public Expression create(List<Expression> args) throws ParseException {
        if (args.size() != 2) {
            String message = String.format("%s function expects 2 arguments.", getName());
            throw new ParseException(message);
        }
        Expression collectionExpression = args.get(0);
        Expression mapExpression = args.get(1);
        return context -> {
            Field field = collectionExpression.evaluate(context);
            List<Field> collection = getCollectionElements(field);
            FieldGroup mapped = AtlasModelFactory.createFieldGroupFrom(field, true);
            mapped.setFieldType(FieldType.ANY);
            for (Field f : collection) {
                ExpressionContext subContext = new ChainedExpressionContext(
                        MapBasedExpressionContext.fromField(
                                f, path -> replacePrefix(path, f.getPath(), field.getPath())),
                        context
                );
                Field result = mapExpression.evaluate(subContext);
                replacePathPrefix(result, result.getPath(), f.getPath());
                mapped.getField().add(result);
            }
            return mapped;
        };
    }
}
