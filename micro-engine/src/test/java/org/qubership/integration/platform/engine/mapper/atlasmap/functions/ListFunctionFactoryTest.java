package org.qubership.integration.platform.engine.mapper.atlasmap.functions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.v2.CollectionType;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldGroup;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ListFunctionFactoryTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldReturnListName() {
        ListFunctionFactory factory = new ListFunctionFactory();

        assertEquals("list", factory.getName());
    }

    @Test
    void shouldReturnEmptyListWhenArgumentsAreEmpty() throws Exception {
        ListFunctionFactory factory = new ListFunctionFactory();

        Expression expression = factory.create(List.of());
        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(FieldGroup.class, result);

        FieldGroup group = (FieldGroup) result;
        assertEquals("result", group.getName());
        assertEquals("/result<>", group.getPath());
        assertEquals(CollectionType.LIST, group.getCollectionType());
        assertTrue(group.getField().isEmpty());
    }

    @Test
    void shouldCreateListFromSingleSimpleField() throws Exception {
        ListFunctionFactory factory = new ListFunctionFactory();
        Expression expressionArgument = mock(Expression.class);

        SimpleField source = MapperTestUtils.simpleFieldWithName("/source/value", "value", "A");
        when(expressionArgument.evaluate(expressionContext)).thenReturn(source);

        Expression expression = factory.create(List.of(expressionArgument));
        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(FieldGroup.class, result);

        FieldGroup group = (FieldGroup) result;
        assertEquals("result", group.getName());
        assertEquals("/result<>", group.getPath());
        assertEquals(CollectionType.LIST, group.getCollectionType());
        assertEquals(1, group.getField().size());

        Field value = group.getField().get(0);
        assertNotSame(source, value);
        assertEquals("A", value.getValue());
        assertEquals("value", value.getName());
        assertEquals("/result<0>", value.getPath());
    }

    @Test
    void shouldFlattenFieldGroupArgumentsIntoResultList() throws Exception {
        ListFunctionFactory factory = new ListFunctionFactory();
        Expression groupExpression = mock(Expression.class);

        SimpleField first = MapperTestUtils.simpleFieldWithName("/source/items<0>", "first", "A");
        SimpleField second = MapperTestUtils.simpleFieldWithName("/source/items<1>", "second", "B");
        FieldGroup sourceGroup = MapperTestUtils.fieldGroup(first, second);

        when(groupExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = mockStatic(FieldUtils.class)) {
            fieldUtils.when(() -> FieldUtils.cloneField(first)).thenReturn(MapperTestUtils.cloneOf(first));
            fieldUtils.when(() -> FieldUtils.cloneField(second)).thenReturn(MapperTestUtils.cloneOf(second));
            fieldUtils.when(() -> FieldUtils.replacePathSegments(any(), any(), any())).thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(groupExpression));
            Field result = expression.evaluate(expressionContext);

            assertInstanceOf(FieldGroup.class, result);

            FieldGroup group = (FieldGroup) result;
            assertEquals(2, group.getField().size());

            Field firstValue = group.getField().get(0);
            assertEquals("A", firstValue.getValue());
            assertEquals("first", firstValue.getName());

            Field secondValue = group.getField().get(1);
            assertEquals("B", secondValue.getValue());
            assertEquals("second", secondValue.getName());
        }
    }

    @Test
    void shouldCombineSimpleFieldsAndFlattenedFieldGroupsInOrder() throws Exception {
        ListFunctionFactory factory = new ListFunctionFactory();
        Expression firstExpression = mock(Expression.class);
        Expression secondExpression = mock(Expression.class);
        Expression thirdExpression = mock(Expression.class);

        SimpleField first = MapperTestUtils.simpleFieldWithName("/source/first", "first", "A");
        SimpleField second = MapperTestUtils.simpleFieldWithName("/source/group<0>", "second", "B");
        SimpleField third = MapperTestUtils.simpleFieldWithName("/source/group<1>", "third", "C");
        SimpleField fourth = MapperTestUtils.simpleFieldWithName("/source/fourth", "fourth", "D");

        FieldGroup group = MapperTestUtils.fieldGroup(second, third);

        when(firstExpression.evaluate(expressionContext)).thenReturn(first);
        when(secondExpression.evaluate(expressionContext)).thenReturn(group);
        when(thirdExpression.evaluate(expressionContext)).thenReturn(fourth);

        try (MockedStatic<FieldUtils> fieldUtils = mockStatic(FieldUtils.class)) {
            fieldUtils.when(() -> FieldUtils.cloneField(first)).thenReturn(MapperTestUtils.cloneOf(first));
            fieldUtils.when(() -> FieldUtils.cloneField(second)).thenReturn(MapperTestUtils.cloneOf(second));
            fieldUtils.when(() -> FieldUtils.cloneField(third)).thenReturn(MapperTestUtils.cloneOf(third));
            fieldUtils.when(() -> FieldUtils.cloneField(fourth)).thenReturn(MapperTestUtils.cloneOf(fourth));
            fieldUtils.when(() -> FieldUtils.replacePathSegments(any(), any(), any())).thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(firstExpression, secondExpression, thirdExpression));
            Field result = expression.evaluate(expressionContext);

            assertInstanceOf(FieldGroup.class, result);

            FieldGroup list = (FieldGroup) result;
            assertEquals(4, list.getField().size());

            assertEquals("A", list.getField().get(0).getValue());
            assertEquals("B", list.getField().get(1).getValue());
            assertEquals("C", list.getField().get(2).getValue());
            assertEquals("D", list.getField().get(3).getValue());
        }
    }

    @Test
    void shouldKeepNullElementsAndContinueIndexing() throws Exception {
        ListFunctionFactory factory = new ListFunctionFactory();
        Expression firstExpression = mock(Expression.class);
        Expression secondExpression = mock(Expression.class);
        Expression thirdExpression = mock(Expression.class);

        SimpleField first = MapperTestUtils.simpleFieldWithName("/source/first", "first", "A");
        SimpleField third = MapperTestUtils.simpleFieldWithName("/source/third", "third", "C");

        when(firstExpression.evaluate(expressionContext)).thenReturn(first);
        when(secondExpression.evaluate(expressionContext)).thenReturn(null);
        when(thirdExpression.evaluate(expressionContext)).thenReturn(third);

        Expression expression = factory.create(List.of(firstExpression, secondExpression, thirdExpression));
        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(FieldGroup.class, result);

        FieldGroup list = (FieldGroup) result;
        assertEquals(3, list.getField().size());

        assertNotNull(list.getField().get(0));
        assertEquals("/result<0>", list.getField().get(0).getPath());
        assertNull(list.getField().get(1));
        assertNotNull(list.getField().get(2));
        assertEquals("/result<2>", list.getField().get(2).getPath());
        assertEquals("C", list.getField().get(2).getValue());
    }

        @Test
        void shouldKeepNullElementsFromNestedFieldGroupAndContinueIndexing() throws Exception {
            ListFunctionFactory factory = new ListFunctionFactory();
            Expression expressionArgument = mock(Expression.class);

            SimpleField first = MapperTestUtils.simpleFieldWithName("/source/group<0>", "first", "A");
            SimpleField third = MapperTestUtils.simpleFieldWithName("/source/group<2>", "third", "C");

            FieldGroup sourceGroup = MapperTestUtils.fieldGroup(first, null, third);
            when(expressionArgument.evaluate(expressionContext)).thenReturn(sourceGroup);

            try (MockedStatic<FieldUtils> fieldUtils = mockStatic(FieldUtils.class)) {
                fieldUtils.when(() -> FieldUtils.cloneField(first)).thenReturn(MapperTestUtils.cloneOf(first));
                fieldUtils.when(() -> FieldUtils.cloneField(third)).thenReturn(MapperTestUtils.cloneOf(third));
                fieldUtils.when(() -> FieldUtils.replacePathSegments(any(), any(), any())).thenAnswer(invocation -> null);

                Expression expression = factory.create(List.of(expressionArgument));
                Field result = expression.evaluate(expressionContext);

                assertInstanceOf(FieldGroup.class, result);

                FieldGroup list = (FieldGroup) result;
                assertEquals(3, list.getField().size());

                assertNotNull(list.getField().get(0));
                assertNull(list.getField().get(1));
                assertNotNull(list.getField().get(2));
                assertEquals("C", list.getField().get(2).getValue());
            }
        }

}
