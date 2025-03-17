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
import io.atlasmap.expression.ExpressionException;
import io.atlasmap.expression.FunctionResolver;
import io.atlasmap.expression.parser.ParseException;

import java.io.StringReader;

public interface CustomExpression extends Expression {
    static Expression parse(String expessionText, FunctionResolver functionResolver) throws ExpressionException {
        if (functionResolver == null) {
            functionResolver = (name, args) -> {
                throw new ParseException("Function not found: " + name);
            };
        }
        Object result = CACHE.get(expessionText);
        if (result instanceof ExpressionException) {
            throw (ExpressionException) result;
        } else if (result instanceof Expression) {
            return (Expression) result;
        } else {
            String actual = expessionText;
            try {
                CustomExpressionParser parser = new CustomExpressionParser(new StringReader(actual));
                parser.functionResolver = functionResolver;
                Expression e = parser.parse();
                CACHE.put(expessionText, e);
                return e;
            } catch (Throwable e) {
                ExpressionException fe = new ExpressionException(actual, e);
                CACHE.put(expessionText, fe);
                throw fe;
            }
        }
    }
}
