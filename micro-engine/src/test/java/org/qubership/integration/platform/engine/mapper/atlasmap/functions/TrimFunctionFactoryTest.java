package org.qubership.integration.platform.engine.mapper.atlasmap.functions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class TrimFunctionFactoryTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldReturnTrimName() {
        TrimFunctionFactory factory = new TrimFunctionFactory();

        assertEquals("trim", factory.getName());
    }

    @Test
    void shouldThrowParseExceptionWhenArgumentsCountIsNotOne() {
        TrimFunctionFactory factory = new TrimFunctionFactory();

        ParseException exception = assertThrows(ParseException.class,
                () -> factory.create(List.of()));

        assertEquals("trim expects 1 argument.", exception.getMessage());
    }

    @Test
    void shouldTrimStringValue() throws Exception {
        TrimFunctionFactory factory = new TrimFunctionFactory();

        Expression expression = factory.create(List.of(MapperTestUtils.expressionReturningWithWrapping(expressionContext, "  hello world  ")));
        Field result = expression.evaluate(expressionContext);

        assertEquals("hello world", result.getValue());
    }

    @Test
    void shouldConvertValueToStringBeforeTrim() throws Exception {
        TrimFunctionFactory factory = new TrimFunctionFactory();

        Expression expression = factory.create(List.of(MapperTestUtils.expressionReturningWithWrapping(expressionContext, new Object() {
            @Override
            public String toString() {
                return "  custom value  ";
            }
        })));
        Field result = expression.evaluate(expressionContext);

        assertEquals("custom value", result.getValue());
    }

    @Test
    void shouldReturnNullWhenValueIsNull() throws Exception {
        TrimFunctionFactory factory = new TrimFunctionFactory();

        Expression expression = factory.create(List.of(MapperTestUtils.expressionReturningWithWrapping(expressionContext, null)));
        Field result = expression.evaluate(expressionContext);

        assertNull(result.getValue());
    }
}
