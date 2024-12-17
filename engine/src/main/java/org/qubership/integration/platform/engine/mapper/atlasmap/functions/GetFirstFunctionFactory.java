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
import io.atlasmap.expression.parser.ParseException;
import io.atlasmap.v2.Field;

import java.util.List;

import static org.qubership.integration.platform.engine.mapper.atlasmap.FieldUtils.getCollectionElements;

public class GetFirstFunctionFactory extends BaseFunctionFactory {
    @Override
    public String getName() {
        return "getFirst";
    }

    @Override
    public Expression create(List<Expression> args) throws ParseException {
        if (args.size() != 1) {
            String message = String.format("%s function expects 1 argument.", getName());
            throw new ParseException(message);
        }
        Expression parentExpression = args.get(0);
        return context -> {
            Field field = parentExpression.evaluate(context);
            List<Field> collection = getCollectionElements(field);
            return collection.isEmpty() ? null : collection.get(0);
        };
    }
}
