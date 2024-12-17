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
import io.atlasmap.v2.AtlasModelFactory;

import static java.util.Objects.isNull;

import java.util.List;

public class ReplaceAllFunctionFactory extends BaseFunctionFactory {
    @Override
    public String getName() {
        return "replaceAll";
    }

    @Override
    public Expression create(List<Expression> args) throws ParseException {
        if (args.size() != 3) {
            String message = String.format("%s expects 3 argument.", getName());
            throw new ParseException(message);
        }
        Expression stringExpression = args.get(0);
        Expression regexExpression = args.get(1);
        Expression replacementExpression = args.get(2);
        return (ctx) -> {
            Object value = stringExpression.evaluate(ctx).getValue();
            Object regex = regexExpression.evaluate(ctx).getValue();
            Object replacement = replacementExpression.evaluate(ctx).getValue();
            return AtlasModelFactory.wrapWithField(
                    isNull(value) || isNull(regex) || isNull(replacement)
                            ? null : value.toString().replaceAll(regex.toString(), replacement.toString()));
        };
    }
}

