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
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.mapper.atlasmap.FieldUtils;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MapperTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MapFunctionFactoryTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldReturnMapName() {
        MapFunctionFactory factory = new MapFunctionFactory();

        assertEquals("map", factory.getName());
    }

    @Test
    void shouldThrowParseExceptionWhenArgumentsCountIsNotTwo() {
        MapFunctionFactory factory = new MapFunctionFactory();

        ParseException exception = assertThrows(ParseException.class,
                () -> factory.create(List.of(mock(Expression.class))));

        assertEquals("map function expects 2 arguments.", exception.getMessage());
    }

    @Test
    void shouldMapEachElementAndReturnMappedFieldGroup() throws Exception {
        MapFunctionFactory factory = new MapFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression mapExpression = mock(Expression.class);

        SimpleField first = MapperTestUtils.simpleFieldWithName("/items<0>", "first", "A");
        SimpleField second = MapperTestUtils.simpleFieldWithName("/items<1>", "second", "B");

        FieldGroup sourceGroup = new FieldGroup();
        sourceGroup.setPath("/items<>");
        sourceGroup.setName("items");
        sourceGroup.getField().add(first);
        sourceGroup.getField().add(second);

        SimpleField firstMapped = MapperTestUtils.simpleFieldWithName("/mapped", "mappedFirst", "X");
        SimpleField secondMapped = MapperTestUtils.simpleFieldWithName("/mapped", "mappedSecond", "Y");

        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);
        when(mapExpression.evaluate(any())).thenReturn(firstMapped, secondMapped);

        try (MockedStatic<FieldUtils> fieldUtils = mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), anyString(), anyString()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, mapExpression));
            Field result = expression.evaluate(expressionContext);

            assertInstanceOf(FieldGroup.class, result);

            FieldGroup mapped = (FieldGroup) result;
            assertEquals(FieldType.ANY, mapped.getFieldType());
            assertEquals(2, mapped.getField().size());
            assertSame(firstMapped, mapped.getField().get(0));
            assertSame(secondMapped, mapped.getField().get(1));

            verify(mapExpression, times(2)).evaluate(any());
            fieldUtils.verify(() -> FieldUtils.replacePathPrefix(same(firstMapped), eq("/mapped"), eq("/items<0>")));
            fieldUtils.verify(() -> FieldUtils.replacePathPrefix(same(secondMapped), eq("/mapped"), eq("/items<1>")));
        }
    }

    @Test
    void shouldReturnEmptyMappedGroupWhenCollectionIsEmpty() throws Exception {
        MapFunctionFactory factory = new MapFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression mapExpression = mock(Expression.class);

        FieldGroup sourceGroup = new FieldGroup();
        sourceGroup.setPath("/items<>");
        sourceGroup.setName("items");

        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        Expression expression = factory.create(List.of(collectionExpression, mapExpression));
        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(FieldGroup.class, result);

        FieldGroup mapped = (FieldGroup) result;
        assertEquals(FieldType.ANY, mapped.getFieldType());
        assertTrue(mapped.getField().isEmpty());

        verifyNoInteractions(mapExpression);
    }
}
