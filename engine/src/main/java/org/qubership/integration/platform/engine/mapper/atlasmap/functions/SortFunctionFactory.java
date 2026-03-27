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
import io.atlasmap.v2.AtlasModelFactory;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldGroup;
import io.atlasmap.v2.FieldType;
import org.qubership.integration.platform.engine.mapper.atlasmap.FieldUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

import static org.qubership.integration.platform.engine.mapper.atlasmap.FieldUtils.*;

public class SortFunctionFactory extends BaseFunctionFactory {
    @Override
    public String getName() {
        return "sort";
    }

    @Override
    public Expression create(List<Expression> args) throws ParseException {
        if (args.size() != 2) {
            String message = String.format("%s function expects 2 arguments.", getName());
            throw new ParseException(message);
        }
        Expression collectionExpression = args.get(0);
        Expression sortingKeyExpression = args.get(1);
        return context -> {
            Field field = collectionExpression.evaluate(context);
            List<Field> collection = getCollectionElements(field);
            FieldGroup sorted = AtlasModelFactory.createFieldGroupFrom(field, true);
            sorted.setFieldType(FieldType.ANY);
            Function<Field, Field> sortingKeyGetter = f -> {
                ExpressionContext subContext = new ChainedExpressionContext(
                        MapBasedExpressionContext.fromField(
                                f, path -> replacePrefix(path, f.getPath(), field.getPath())),
                        context
                );
                try {
                    return sortingKeyExpression.evaluate(subContext);
                } catch (ExpressionException exception) {
                    return null;
                }
            };
            Comparator<Field> comparator = getFieldComparator(sortingKeyGetter);
            collection.stream().sorted(comparator).forEachOrdered(f -> {
                replacePathPrefix(f, f.getPath(), field.getPath());
                sorted.getField().add(f);
            });
            return sorted;
        };
    }

    private static Comparator<Field> getFieldComparator(Function<Field, Field> sortingKeyGetter) {
        Comparator<Field> scalarFieldComparator = getScalarFieldComparator();
        Comparator<Field> collectionFieldComparator = getFieldCollectionComparator(scalarFieldComparator);
        return Comparator.comparing(
                sortingKeyGetter,
                Comparator.<Field, Boolean>comparing(Objects::isNull)
                        .thenComparing(SortFunctionFactory::getFieldType)
                        .thenComparing(field -> hasNotIndexedCollection(new AtlasPath(field.getPath())))
                        .thenComparing((field1, field2) -> {
                            Comparator<Field> comparator = hasNotIndexedCollection(new AtlasPath(field1.getPath()))
                                    ? collectionFieldComparator
                                    : scalarFieldComparator;
                            return comparator.compare(field1, field2);
                        })
        );
    }

    private static Comparator<Field> getFieldCollectionComparator(Comparator<Field> elementComparator) {
        return Comparator.comparing(
                FieldUtils::getCollectionElements,
                Comparator.<List<Field>, Integer>comparing(Collection::size)
                        .thenComparing((l1, l2) -> {
                            int l = l1.size();
                            for (int i = 0; i < l; ++i) {
                                int res = elementComparator.compare(l1.get(i), l2.get(i));
                                if (res != 0) {
                                    return res;
                                }
                            }
                            return 0;
                        })
        );
    }

    private static Comparator<Field> getScalarFieldComparator() {
        return (field1, field2) -> {
            FieldType fieldType = getFieldType(field1);
            Comparator<Object> valueComparator = switch (fieldType) {
                case STRING -> Comparator.comparing(String::valueOf);
                case CHAR -> Comparator.comparing(value -> Character.toString((char) value));
                case BOOLEAN -> Comparator.comparing(value -> (boolean) value);
                case INTEGER -> Comparator.comparing(value -> (Integer) value);
                case DOUBLE, FLOAT, NUMBER -> Comparator.comparing(value -> new BigDecimal(String.valueOf(value)));
                case SHORT -> Comparator.comparing(value -> Short.valueOf(String.valueOf(value)));
                case LONG -> Comparator.comparing(value -> Long.valueOf(String.valueOf(value)));
                case BYTE -> Comparator.comparing(value -> Byte.valueOf(String.valueOf(value)));
                case BIG_INTEGER -> Comparator.comparing(value -> new BigInteger(String.valueOf(value)));
                default -> Comparator.comparing(String::valueOf);
            };
            return Comparator.comparing(
                    Field::getValue,
                    Comparator.comparing(Objects::nonNull).thenComparing(valueComparator)
            ).compare(field1, field2);
        };
    }

    private static FieldType getFieldType(Field field) {
        Optional<Field> optionalField = Optional.ofNullable(field);
        return optionalField
                .map(Field::getFieldType)
                .or(() -> optionalField.map(Field::getValue).map(SortFunctionFactory::getFieldTypeFromValue))
                .orElse(FieldType.NONE);
    }

    private static FieldType getFieldTypeFromValue(Object value) {
        if (value instanceof String) {
            return FieldType.STRING;
        } else if (value instanceof Character) {
            return FieldType.CHAR;
        } else if (value instanceof Boolean) {
            return FieldType.BOOLEAN;
        } else if (value instanceof Integer) {
            return FieldType.INTEGER;
        } else if (value instanceof Double || value instanceof Float) {
            return FieldType.NUMBER;
        } else if (value instanceof Short) {
            return FieldType.SHORT;
        } else if (value instanceof Long) {
            return FieldType.LONG;
        } else if (value instanceof Byte) {
            return FieldType.BYTE;
        } else if (value instanceof BigInteger) {
            return FieldType.BIG_INTEGER;
        } else {
            return FieldType.NONE;
        }
    }
}
