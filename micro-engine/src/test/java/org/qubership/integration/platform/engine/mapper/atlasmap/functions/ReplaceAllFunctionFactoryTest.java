package org.qubership.integration.platform.engine.mapper.atlasmap.functions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.parser.ParseException;
import io.atlasmap.v2.Field;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MapperTestUtils;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ReplaceAllFunctionFactoryTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldReturnReplaceAllName() {
        ReplaceAllFunctionFactory factory = new ReplaceAllFunctionFactory();

        assertEquals("replaceAll", factory.getName());
    }

    @Test
    void shouldThrowParseExceptionWhenArgumentsCountIsNotThree() {
        ReplaceAllFunctionFactory factory = new ReplaceAllFunctionFactory();

        ParseException exception = assertThrows(ParseException.class,
                () -> factory.create(List.of(mock(Expression.class), mock(Expression.class))));

        assertEquals("replaceAll expects 3 argument.", exception.getMessage());
    }

    @Test
    void shouldReplaceAllMatchesWhenAllArgumentsPresent() throws Exception {
        ReplaceAllFunctionFactory factory = new ReplaceAllFunctionFactory();

        Expression expression = factory.create(List.of(
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, "a-b-c"),
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, "-"),
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, "_")
        ));

        Field result = expression.evaluate(expressionContext);

        assertEquals("a_b_c", result.getValue());
    }

    @Test
    void shouldConvertArgumentsToStringBeforeReplacing() throws Exception {
        ReplaceAllFunctionFactory factory = new ReplaceAllFunctionFactory();

        Expression expression = factory.create(List.of(
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, 123123),
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, 23),
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, 99)
        ));

        Field result = expression.evaluate(expressionContext);

        assertEquals("199199", result.getValue());
    }

    @ParameterizedTest
    @MethodSource("nullArguments")
    void shouldReturnNullWhenAnyArgumentIsNull(Object value, Object regex, Object replacement) throws Exception {
        ReplaceAllFunctionFactory factory = new ReplaceAllFunctionFactory();

        Expression expression = factory.create(List.of(
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, value),
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, regex),
                MapperTestUtils.expressionReturningWithWrapping(expressionContext, replacement)
        ));

        Field result = expression.evaluate(expressionContext);

        assertNull(result.getValue());
    }

    private static Stream<Arguments> nullArguments() {
        return Stream.of(
                Arguments.of(null, "a", "b"),
                Arguments.of("abc", null, "b"),
                Arguments.of("abc", "a", null)
        );
    }
}
