package org.qubership.integration.platform.engine.mapper.atlasmap.functions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.parser.ParseException;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldGroup;
import io.atlasmap.v2.SimpleField;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class GetFirstFunctionFactoryTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldReturnGetFirstName() {
        GetFirstFunctionFactory factory = new GetFirstFunctionFactory();

        assertEquals("getFirst", factory.getName());
    }

    @Test
    void shouldThrowParseExceptionWhenArgumentsCountIsNotOne() {
        GetFirstFunctionFactory factory = new GetFirstFunctionFactory();

        ParseException exception = assertThrows(ParseException.class, () -> factory.create(List.of()));

        assertEquals("getFirst function expects 1 argument.", exception.getMessage());
    }

    @Test
    void shouldReturnFirstElementWhenCollectionIsNotEmpty() throws Exception {
        GetFirstFunctionFactory factory = new GetFirstFunctionFactory();
        Expression parentExpression = mock(Expression.class);

        SimpleField first = MapperTestUtils.simpleField("/items[0]", "A");
        SimpleField second = MapperTestUtils.simpleField("/items[1]", "B");
        FieldGroup group = MapperTestUtils.fieldGroupSimple(first, second);

        when(parentExpression.evaluate(expressionContext)).thenReturn(group);

        Expression expression = factory.create(List.of(parentExpression));
        Field result = expression.evaluate(expressionContext);

        assertSame(first, result);
    }

    @Test
    void shouldReturnNullWhenCollectionIsEmpty() throws Exception {
        GetFirstFunctionFactory factory = new GetFirstFunctionFactory();
        Expression parentExpression = mock(Expression.class);
        FieldGroup group = MapperTestUtils.fieldGroupSimple();

        when(parentExpression.evaluate(expressionContext)).thenReturn(group);

        Expression expression = factory.create(List.of(parentExpression));
        Field result = expression.evaluate(expressionContext);

        assertNull(result);
    }
}
