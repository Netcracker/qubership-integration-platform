package org.qubership.integration.platform.engine.mapper.atlasmap.functions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.parser.ParseException;
import io.atlasmap.v2.CollectionType;
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
class GetKeysFunctionFactoryTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldReturnGetKeysName() {
        GetKeysFunctionFactory factory = new GetKeysFunctionFactory();

        assertEquals("getKeys", factory.getName());
    }

    @Test
    void shouldThrowParseExceptionWhenArgumentsCountIsNotOne() {
        GetKeysFunctionFactory factory = new GetKeysFunctionFactory();

        ParseException exception = assertThrows(ParseException.class, () -> factory.create(List.of()));

        assertEquals("getKeys function expects 1 argument.", exception.getMessage());
    }

    @Test
    void shouldReturnKeysGroupWhenParentHasChildren() throws Exception {
        GetKeysFunctionFactory factory = new GetKeysFunctionFactory();
        Expression parentExpression = mock(Expression.class);

        FieldGroup parent = new FieldGroup();
        parent.setPath("/root");
        parent.setName("root");

        SimpleField first = MapperTestUtils.child("first");
        SimpleField second = MapperTestUtils.child("second");
        parent.getField().add(first);
        parent.getField().add(second);

        when(parentExpression.evaluate(expressionContext)).thenReturn(parent);

        Expression expression = factory.create(List.of(parentExpression));
        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(FieldGroup.class, result);

        FieldGroup keys = (FieldGroup) result;
        assertEquals(FieldType.STRING, keys.getFieldType());
        assertEquals(CollectionType.ARRAY, keys.getCollectionType());
        assertEquals("/root/$keys<>", keys.getPath());
        assertEquals("keys", keys.getName());
        assertEquals(2, keys.getField().size());

        Field firstKey = keys.getField().getFirst();
        assertEquals(CollectionType.NONE, firstKey.getCollectionType());
        assertEquals(FieldType.STRING, firstKey.getFieldType());
        assertEquals("first", firstKey.getName());
        assertEquals("first", firstKey.getValue());
        assertEquals("/root/$keys<0>", firstKey.getPath());

        Field secondKey = keys.getField().get(1);
        assertEquals(CollectionType.NONE, secondKey.getCollectionType());
        assertEquals(FieldType.STRING, secondKey.getFieldType());
        assertEquals("second", secondKey.getName());
        assertEquals("second", secondKey.getValue());
        assertEquals("/root/$keys<1>", secondKey.getPath());
    }

    @Test
    void shouldReturnEmptyKeysGroupWhenParentHasNoChildren() throws Exception {
        GetKeysFunctionFactory factory = new GetKeysFunctionFactory();
        Expression parentExpression = mock(Expression.class);

        FieldGroup parent = new FieldGroup();
        parent.setPath("/root");
        parent.setName("root");

        when(parentExpression.evaluate(expressionContext)).thenReturn(parent);

        Expression expression = factory.create(List.of(parentExpression));
        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(FieldGroup.class, result);

        FieldGroup keys = (FieldGroup) result;
        assertEquals(FieldType.STRING, keys.getFieldType());
        assertEquals(CollectionType.ARRAY, keys.getCollectionType());
        assertEquals("/root/$keys<>", keys.getPath());
        assertEquals("keys", keys.getName());
        assertTrue(keys.getField().isEmpty());
    }
}
