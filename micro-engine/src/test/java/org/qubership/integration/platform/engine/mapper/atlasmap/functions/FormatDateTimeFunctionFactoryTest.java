package org.qubership.integration.platform.engine.mapper.atlasmap.functions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.ExpressionException;
import io.atlasmap.expression.parser.ParseException;
import io.atlasmap.v2.Field;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MapperTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class FormatDateTimeFunctionFactoryTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldReturnFormatDateTimeName() {
        FormatDateTimeFunctionFactory factory = new FormatDateTimeFunctionFactory();

        assertEquals("formatDateTime", factory.getName());
    }

    @Test
    void shouldThrowParseExceptionWhenArgumentsCountLessThanTwo() {
        FormatDateTimeFunctionFactory factory = new FormatDateTimeFunctionFactory();

        ParseException exception = assertThrows(ParseException.class, () -> factory.create(List.of()));

        assertEquals("formatDateTime expects from 2 to 10 argument.", exception.getMessage());
    }

    @Test
    void shouldThrowParseExceptionWhenArgumentsCountGreaterThanTen() {
        FormatDateTimeFunctionFactory factory = new FormatDateTimeFunctionFactory();

        ParseException exception = assertThrows(ParseException.class, () -> factory.create(List.of(
                mock(Expression.class),
                mock(Expression.class),
                mock(Expression.class),
                mock(Expression.class),
                mock(Expression.class),
                mock(Expression.class),
                mock(Expression.class),
                mock(Expression.class),
                mock(Expression.class),
                mock(Expression.class),
                mock(Expression.class)
        )));

        assertEquals("formatDateTime expects from 2 to 10 argument.", exception.getMessage());
    }

    @Test
    void shouldFormatDateTimeWithRequiredArgumentsAndDefaults() throws Exception {
        FormatDateTimeFunctionFactory factory = new FormatDateTimeFunctionFactory();

        Expression expression = factory.create(List.of(
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, "yyyy-MM-dd HH:mm:ss.SSS"),
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, "2024")
        ));

        Field result = expression.evaluate(expressionContext);

        assertEquals("2024-01-01 00:00:00.000", result.getValue());
    }

    @Test
    void shouldFormatDateTimeWithAllArgumentsIncludingTimezoneAndLocale() throws Exception {
        FormatDateTimeFunctionFactory factory = new FormatDateTimeFunctionFactory();

        Expression expression = factory.create(List.of(
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, "MMMM yyyy HH:mm:ss.SSS XXX"),
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, "2024"),
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, "2"),
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, "3"),
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, "4"),
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, "5"),
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, "6"),
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, "7"),
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, "UTC"),
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, "en-US")
        ));

        Field result = expression.evaluate(expressionContext);

        assertEquals("February 2024 04:05:06.007 Z", result.getValue());
    }

    @Test
    void shouldThrowExpressionExceptionWhenFormatStringNotSet() throws Exception {
        FormatDateTimeFunctionFactory factory = new FormatDateTimeFunctionFactory();

        Expression expression = factory.create(List.of(
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, null),
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, "2024")
        ));

        ExpressionException exception = assertThrows(ExpressionException.class, () -> expression.evaluate(expressionContext));

        assertEquals("Format string not set", exception.getMessage());
    }

    @Test
    void shouldThrowNumberFormatExceptionWhenIntegerArgumentIsInvalid() throws Exception {
        FormatDateTimeFunctionFactory factory = new FormatDateTimeFunctionFactory();

        Expression expression = factory.create(List.of(
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, "yyyy"),
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, "not-a-number")
        ));

        assertThrows(NumberFormatException.class, () -> expression.evaluate(expressionContext));
    }
}
