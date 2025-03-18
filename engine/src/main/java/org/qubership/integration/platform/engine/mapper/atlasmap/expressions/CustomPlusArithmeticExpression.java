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

package org.qubership.integration.platform.engine.mapper.atlasmap.expressions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.ExpressionException;
import io.atlasmap.expression.internal.ArithmeticExpression;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldType;

import static io.atlasmap.v2.AtlasModelFactory.wrapWithField;

/**
 * Change default `+` operator behavior for string variables
 */
public abstract class CustomPlusArithmeticExpression extends ArithmeticExpression {

    public CustomPlusArithmeticExpression(Expression left, Expression right) {
        super(left, right);
    }

    public static Expression createCustomPlus(Expression left, Expression right) {
        return new CustomPlusArithmeticExpression(left, right) {
            // only number or string
            protected Field evaluate(Field lfield, Field rfield) {
                Object lvalue = lfield.getValue();
                Object rvalue = rfield.getValue();
                if (lfield.getFieldType() == FieldType.STRING || lvalue instanceof String) {
                    if (rvalue instanceof Number number) {
                        rvalue = number.toString();
                    }
                    String answer = replaceNullValue((String) lvalue) + replaceNullValue((String) rvalue);
                    return wrapWithField(answer);
                } else {
                    return wrapWithField(plus(asNumber(lvalue), asNumber(rvalue)));
                }
            }

            public String getExpressionSymbol() {
                return "+";
            }
        };
    }

    @Override
    public Field evaluate(ExpressionContext message) throws ExpressionException {
        Field lfield = left.evaluate(message);
        Field rfield = right.evaluate(message);

        if (lfield == null || rfield == null
                || (lfield.getFieldType() != null && lfield.getFieldType() != FieldType.STRING)
                || (lfield.getFieldType() == null && !(lfield.getValue() instanceof String))
        ) {
            // default branch
            if (lfield == null || lfield.getValue() == null) {
                return wrapWithField(null);
            }
            if (rfield == null || rfield.getValue() == null) {
                return null;
            }
        }
        return evaluate(lfield, rfield);
    }

    private static String replaceNullValue(String value) {
        return value == null ? "" : value;
    }
}
