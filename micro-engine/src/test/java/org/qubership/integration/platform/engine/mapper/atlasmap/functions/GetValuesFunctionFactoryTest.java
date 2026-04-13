package org.qubership.integration.platform.engine.mapper.atlasmap.functions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.parser.ParseException;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldGroup;
import io.atlasmap.v2.FieldType;
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
class GetValuesFunctionFactoryTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldReturnGetValuesName() {
        GetValuesFunctionFactory factory = new GetValuesFunctionFactory();

        assertEquals("getValues", factory.getName());
    }

    @Test
    void shouldThrowParseExceptionWhenArgumentsCountIsNotOne() {
        GetValuesFunctionFactory factory = new GetValuesFunctionFactory();

        ParseException exception = assertThrows(ParseException.class, () -> factory.create(List.of()));

        assertEquals("getValues function expects 1 argument.", exception.getMessage());
    }

    @Test
    void shouldReturnValuesGroupWhenParentHasChildren() throws Exception {
        GetValuesFunctionFactory factory = new GetValuesFunctionFactory();
        Expression parentExpression = mock(Expression.class);

        FieldGroup parent = new FieldGroup();
        parent.setPath("/root");
        parent.setName("root");
        parent.setFieldType(FieldType.COMPLEX);

        SimpleField first = MapperTestUtils.child("/root/first", "first", "A");
        SimpleField second = MapperTestUtils.child("/root/second", "second", "B");
        parent.getField().add(first);
        parent.getField().add(second);

        when(parentExpression.evaluate(expressionContext)).thenReturn(parent);

        Expression expression = factory.create(List.of(parentExpression));
        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(FieldGroup.class, result);

        FieldGroup values = (FieldGroup) result;
        assertEquals(FieldType.ANY, values.getFieldType());
        assertEquals("/root/$values<>", values.getPath());
        assertEquals("values", values.getName());
        assertEquals(2, values.getField().size());

        Field firstValue = values.getField().getFirst();
        assertNotSame(first, firstValue);
        assertEquals("A", firstValue.getValue());
        assertEquals("first", firstValue.getName());
        assertEquals("/root/$values<0>", firstValue.getPath());

        Field secondValue = values.getField().get(1);
        assertNotSame(second, secondValue);
        assertEquals("B", secondValue.getValue());
        assertEquals("second", secondValue.getName());
        assertEquals("/root/$values<1>", secondValue.getPath());
    }

    @Test
    void shouldKeepNullChildAndIncrementIndexForNextValues() throws Exception {
        GetValuesFunctionFactory factory = new GetValuesFunctionFactory();
        Expression parentExpression = mock(Expression.class);

        FieldGroup parent = new FieldGroup();
        parent.setPath("/root");
        parent.setName("root");

        SimpleField first = MapperTestUtils.child("/root/first", "first", "A");
        SimpleField third = MapperTestUtils.child("/root/third", "third", "C");
        parent.getField().add(first);
        parent.getField().add(null);
        parent.getField().add(third);

        when(parentExpression.evaluate(expressionContext)).thenReturn(parent);

        Expression expression = factory.create(List.of(parentExpression));
        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(FieldGroup.class, result);

        FieldGroup values = (FieldGroup) result;
        assertEquals(3, values.getField().size());
        assertNull(values.getField().get(1));

        Field firstValue = values.getField().get(0);
        assertEquals("/root/$values<0>", firstValue.getPath());

        Field thirdValue = values.getField().get(2);
        assertEquals("/root/$values<2>", thirdValue.getPath());
        assertEquals("C", thirdValue.getValue());
    }

    @Test
    void shouldReturnEmptyValuesGroupWhenParentHasNoChildren() throws Exception {
        GetValuesFunctionFactory factory = new GetValuesFunctionFactory();
        Expression parentExpression = mock(Expression.class);

        FieldGroup parent = new FieldGroup();
        parent.setPath("/root");
        parent.setName("root");

        when(parentExpression.evaluate(expressionContext)).thenReturn(parent);

        Expression expression = factory.create(List.of(parentExpression));
        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(FieldGroup.class, result);

        FieldGroup values = (FieldGroup) result;
        assertEquals(FieldType.ANY, values.getFieldType());
        assertEquals("/root/$values<>", values.getPath());
        assertEquals("values", values.getName());
        assertTrue(values.getField().isEmpty());
    }
}
