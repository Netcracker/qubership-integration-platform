package org.qubership.integration.platform.engine.mapper.atlasmap.functions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.internal.BooleanExpression;
import io.atlasmap.expression.internal.ConstantExpression;
import io.atlasmap.expression.parser.ParseException;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldGroup;
import io.atlasmap.v2.FieldType;
import io.atlasmap.v2.SimpleField;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MapperTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class FilterFunctionFactoryTest {

    @Test
    void shouldReturnFilterByName() {
        FilterFunctionFactory factory = new FilterFunctionFactory();

        assertEquals("filterBy", factory.getName());
    }

    @Test
    void shouldThrowParseExceptionWhenArgumentsCountIsNotTwo() {
        FilterFunctionFactory factory = new FilterFunctionFactory();
        Expression expression = mock(Expression.class);

        ParseException exception = assertThrows(ParseException.class,
                () -> factory.create(List.of(expression)));

        assertEquals("filterBy function expects 2 arguments.", exception.getMessage());
    }

    @Test
    void shouldReturnAllElementsWhenFilterExpressionIsTrueConstant() throws Exception {
        FilterFunctionFactory factory = new FilterFunctionFactory();
        ExpressionContext context = mock(ExpressionContext.class);
        Expression collectionExpression = mock(Expression.class);

        FieldGroup sourceGroup = MapperTestUtils.fieldGroup(
                MapperTestUtils.simpleField("/items[0]", "A"),
                MapperTestUtils.simpleField("/items[1]", "B"));

        when(collectionExpression.evaluate(context)).thenReturn(sourceGroup);

        Expression expression = factory.create(List.of(collectionExpression, ConstantExpression.TRUE));
        Field result = expression.evaluate(context);

        assertInstanceOf(FieldGroup.class, result);

        FieldGroup filtered = (FieldGroup) result;
        assertEquals(FieldType.ANY, filtered.getFieldType());
        assertEquals(2, filtered.getField().size());
        assertSame(sourceGroup.getField().get(0), filtered.getField().get(0));
        assertSame(sourceGroup.getField().get(1), filtered.getField().get(1));
    }

    @Test
    void shouldReturnOnlyMatchingElementsWhenBooleanExpressionMatchesSubset() throws Exception {
        FilterFunctionFactory factory = new FilterFunctionFactory();
        ExpressionContext context = mock(ExpressionContext.class);
        Expression collectionExpression = mock(Expression.class);
        BooleanExpression filterExpression = mock(BooleanExpression.class);

        SimpleField first = MapperTestUtils.simpleField("/items[0]", "A");
        SimpleField second = MapperTestUtils.simpleField("/items[1]", "B");
        FieldGroup sourceGroup = MapperTestUtils.fieldGroup(first, second);

        when(collectionExpression.evaluate(context)).thenReturn(sourceGroup);
        when(filterExpression.matches(any())).thenReturn(true, false);

        Expression expression = factory.create(List.of(collectionExpression, filterExpression));
        Field result = expression.evaluate(context);

        assertInstanceOf(FieldGroup.class, result);

        FieldGroup filtered = (FieldGroup) result;
        assertEquals(FieldType.ANY, filtered.getFieldType());
        assertEquals(1, filtered.getField().size());
        assertSame(first, filtered.getField().getFirst());

        verify(filterExpression, times(2)).matches(any());
    }

    @Test
    void shouldReturnEmptyGroupWhenBooleanExpressionDoesNotMatchAnyElement() throws Exception {
        FilterFunctionFactory factory = new FilterFunctionFactory();
        ExpressionContext context = mock(ExpressionContext.class);
        Expression collectionExpression = mock(Expression.class);
        BooleanExpression filterExpression = mock(BooleanExpression.class);

        FieldGroup sourceGroup = MapperTestUtils.fieldGroup(
                MapperTestUtils.simpleField("/items[0]", "A"),
                MapperTestUtils.simpleField("/items[1]", "B"));

        when(collectionExpression.evaluate(context)).thenReturn(sourceGroup);
        when(filterExpression.matches(any())).thenReturn(false);

        Expression expression = factory.create(List.of(collectionExpression, filterExpression));
        Field result = expression.evaluate(context);

        assertInstanceOf(FieldGroup.class, result);

        FieldGroup filtered = (FieldGroup) result;
        assertEquals(FieldType.ANY, filtered.getFieldType());
        assertTrue(filtered.getField().isEmpty());

        verify(filterExpression, times(2)).matches(any());
    }
}
