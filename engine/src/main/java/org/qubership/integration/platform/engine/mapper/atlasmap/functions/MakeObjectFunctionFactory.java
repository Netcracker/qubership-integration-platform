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

import io.atlasmap.core.AtlasPath;
import io.atlasmap.core.BaseFunctionFactory;
import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.ExpressionException;
import io.atlasmap.expression.parser.ParseException;
import io.atlasmap.v2.CollectionType;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldType;
import io.atlasmap.v2.SimpleField;
import org.qubership.integration.platform.engine.mapper.atlasmap.FieldUtils;
import org.qubership.integration.platform.mapper.ComplexField;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.isNull;
import static org.qubership.integration.platform.engine.mapper.atlasmap.FieldUtils.replacePathSegments;

public class MakeObjectFunctionFactory extends BaseFunctionFactory {
    @Override
    public String getName() {
        return "makeObject";
    }

    @Override
    public Expression create(List<Expression> args) throws ParseException {
        if (args.size() % 2 != 0) {
            String message = String.format("%s expects even number of arguments.", getName());
            throw new ParseException(message);
        }
        return ctx -> {
            List<Field> fields = new ArrayList<>();
            for (int i = 0; i < args.size() / 2; ++i) {
                Optional<String> optionalName = evaluate(args.get(i * 2), ctx).map(Field::getValue).map(Object::toString);
                Optional<Field> value = evaluate(args.get(i * 2 + 1), ctx);
                optionalName.map(name ->
                    value.map(valueField -> {
                        Field f = FieldUtils.cloneField(valueField);
                        String prefix = "/result/" + name + getCollectionSuffix(valueField.getCollectionType());
                        replacePathSegments(
                                f,
                                new AtlasPath(f.getPath()).getSegments(true),
                                new AtlasPath(prefix).getSegments(true)
                        );
                        f.setName(name);
                        return f;
                    }).orElseGet(() -> {
                        SimpleField f = new SimpleField();
                        f.setPath("/result/" + name);
                        f.setName(name);
                        return f;
                    })
                ).ifPresent(fields::add);
            }
            ComplexField result = new ComplexField(fields);
            result.setFieldType(FieldType.COMPLEX);
            result.setCollectionType(CollectionType.NONE);
            result.setPath("/result");
            result.setName("result");
            return result;
        };
    }

    private Optional<Field> evaluate(Expression expression, ExpressionContext ctx) throws ExpressionException {
        return isNull(expression) ? Optional.empty() : Optional.of(expression.evaluate(ctx));
    }

    private String getCollectionSuffix(CollectionType collectionType) {
        return CollectionType.LIST.equals(collectionType) || CollectionType.ARRAY.equals(collectionType)
                ? AtlasPath.PATH_LIST_START + AtlasPath.PATH_LIST_END
                : "";
    }
}
