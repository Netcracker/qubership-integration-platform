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
import io.atlasmap.expression.ExpressionException;
import io.atlasmap.expression.parser.ParseException;
import io.atlasmap.v2.AtlasModelFactory;
import io.atlasmap.v2.Field;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;

import static java.util.Objects.isNull;

public class FormatDateTimeFunctionFactory extends BaseFunctionFactory {
    @Override
    public String getName() {
        return "formatDateTime";
    }

    @Override
    public Expression create(List<Expression> args) throws ParseException {
        int argCount = args.size();
        if (argCount < 2 || argCount > 10) {
            String message = String.format("%s expects from 2 to 10 argument.", getName());
            throw new ParseException(message);
        }
        Expression formatStringExpression = args.get(0);
        Expression yearExpression = args.get(1);
        Expression monthExpression = (argCount > 2) ? args.get(2) : null;
        Expression dayExpression = (argCount > 3) ? args.get(3) : null;
        Expression hourExpression = (argCount > 4) ? args.get(4) : null;
        Expression minuteExpression = (argCount > 5) ? args.get(5) : null;
        Expression secondExpression = (argCount > 6) ? args.get(6) : null;
        Expression millisecondExpression = (argCount > 7) ? args.get(7) : null;
        Expression timezoneExpression = (argCount > 8) ? args.get(8) : null;
        Expression localeExpression = (argCount > 9) ? args.get(9) : null;
        return (ctx) -> {
            Optional<String> formatString = evaluateArgument(formatStringExpression, ctx);
            Optional<Integer> year = evaluateIntegerArgument(yearExpression, ctx);
            Optional<Integer> month = evaluateIntegerArgument(monthExpression, ctx);
            Optional<Integer> day = evaluateIntegerArgument(dayExpression, ctx);
            Optional<Integer> hour = evaluateIntegerArgument(hourExpression, ctx);
            Optional<Integer> minute = evaluateIntegerArgument(minuteExpression, ctx);
            Optional<Integer> second = evaluateIntegerArgument(secondExpression, ctx);
            Optional<Integer> millisecond = evaluateIntegerArgument(millisecondExpression, ctx);
            Optional<TimeZone> timezone = evaluateArgument(timezoneExpression, ctx).map(TimeZone::getTimeZone);
            Optional<Locale> locale = evaluateArgument(localeExpression, ctx).map(Locale::forLanguageTag);

            DateTimeFormatter formatter = formatString.map(s -> DateTimeFormatter.ofPattern(s, locale.orElse(Locale.getDefault())))
                    .orElseThrow(() -> new ExpressionException("Format string not set"));

            OffsetDateTime timestamp = OffsetDateTime.of(
                    year.orElse(0),
                    month.orElse(1),
                    day.orElse(1),
                    hour.orElse(0),
                    minute.orElse(0),
                    second.orElse(0),
                    millisecond.orElse(0) * 1000000,
                    timezone.orElse(TimeZone.getDefault()).toZoneId().getRules().getOffset(Instant.now())
            );
            return AtlasModelFactory.wrapWithField(formatter.format(timestamp));
        };
    }

    private Optional<String> evaluateArgument(Expression expression, ExpressionContext ctx) throws ExpressionException {
        return isNull(expression) ? Optional.empty() : Optional.ofNullable(expression.evaluate(ctx)).map(Field::getValue).map(Object::toString);
    }

    private Optional<Integer> evaluateIntegerArgument(Expression expression, ExpressionContext ctx) throws ExpressionException {
        return evaluateArgument(expression, ctx).map(Integer::valueOf);
    }
}
