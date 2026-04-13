package org.qubership.integration.platform.engine.mapper.atlasmap.functions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.parser.ParseException;
import io.atlasmap.v2.CollectionType;
import io.atlasmap.v2.Field;
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
import org.qubership.integration.platform.mapper.ComplexField;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.qubership.integration.platform.engine.mapper.atlasmap.FieldUtils.getChildren;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MakeObjectFunctionFactoryTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldReturnMakeObjectName() {
        MakeObjectFunctionFactory factory = new MakeObjectFunctionFactory();

        assertEquals("makeObject", factory.getName());
    }

    @Test
    void shouldThrowParseExceptionWhenArgumentsCountIsOdd() {
        MakeObjectFunctionFactory factory = new MakeObjectFunctionFactory();

        ParseException exception = assertThrows(ParseException.class,
                () -> factory.create(List.of(mock(Expression.class))));

        assertEquals("makeObject expects even number of arguments.", exception.getMessage());
    }

    @Test
    void shouldCreateComplexObjectWhenNameAndValueArePresent() throws Exception {
        MakeObjectFunctionFactory factory = new MakeObjectFunctionFactory();

        Expression nameExpression = MapperTestUtils.expressionReturningWithWrapping(expressionContext, "customerId");
        Expression valueExpression =
                MapperTestUtils.expressionReturningField(expressionContext, MapperTestUtils.sourceField("/source/id", "sourceName", "123", CollectionType.NONE));

        SimpleField cloned = MapperTestUtils.sourceField("/source/id", "sourceName", "123", CollectionType.NONE);
        Field result;

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class)) {
            fieldUtils.when(() -> FieldUtils.cloneField(any(Field.class))).thenReturn(cloned);
            fieldUtils.when(() -> FieldUtils.replacePathSegments(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(nameExpression, valueExpression));
            result = expression.evaluate(expressionContext);
        }

        assertInstanceOf(ComplexField.class, result);

        ComplexField complex = (ComplexField) result;
        assertEquals(FieldType.COMPLEX, complex.getFieldType());
        assertEquals(CollectionType.NONE, complex.getCollectionType());
        assertEquals("/result", complex.getPath());
        assertEquals("result", complex.getName());

        List<Field> children = getChildren(complex);
        assertEquals(1, children.size());

        Field child = children.getFirst();
        assertSame(cloned, child);
        assertEquals("customerId", child.getName());
        assertEquals("123", child.getValue());
    }

    @Test
    void shouldUseListSuffixWhenValueCollectionTypeIsList() throws Exception {
        MakeObjectFunctionFactory factory = new MakeObjectFunctionFactory();

        Expression nameExpression = MapperTestUtils.expressionReturningWithWrapping(expressionContext, "items");
        Expression valueExpression =
                MapperTestUtils.expressionReturningField(expressionContext, MapperTestUtils.sourceField("/source/items<>", "items", "A", CollectionType.LIST));

        SimpleField cloned = MapperTestUtils.sourceField("/source/items<>", "items", "A", CollectionType.LIST);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class)) {
            fieldUtils.when(() -> FieldUtils.cloneField(any(Field.class))).thenReturn(cloned);
            fieldUtils.when(() -> FieldUtils.replacePathSegments(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(nameExpression, valueExpression));
            expression.evaluate(expressionContext);

            fieldUtils.verify(() -> FieldUtils.replacePathSegments(
                    same(cloned),
                    any(),
                    argThat(targetSegments ->
                            targetSegments != null
                                    && targetSegments.toString().contains("expression=result")
                                    && targetSegments.toString().contains("expression=items<>"))
            ));
        }
    }

    @Test
    void shouldUseListSuffixWhenValueCollectionTypeIsArray() throws Exception {
        MakeObjectFunctionFactory factory = new MakeObjectFunctionFactory();

        Expression nameExpression = MapperTestUtils.expressionReturningWithWrapping(expressionContext, "items");
        Expression valueExpression =
                MapperTestUtils.expressionReturningField(expressionContext, MapperTestUtils.sourceField("/source/items<>", "items", "A", CollectionType.ARRAY));

        SimpleField cloned = MapperTestUtils.sourceField("/source/items<>", "items", "A", CollectionType.ARRAY);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class)) {
            fieldUtils.when(() -> FieldUtils.cloneField(any(Field.class))).thenReturn(cloned);
            fieldUtils.when(() -> FieldUtils.replacePathSegments(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(nameExpression, valueExpression));
            expression.evaluate(expressionContext);

            fieldUtils.verify(() -> FieldUtils.replacePathSegments(
                    same(cloned),
                    any(),
                    argThat(targetSegments ->
                            targetSegments != null
                                    && targetSegments.toString().contains("expression=result")
                                    && targetSegments.toString().contains("expression=items<>"))
            ));
        }
    }

    @Test
    void shouldCreateEmptySimpleFieldWhenValueExpressionIsNull() throws Exception {
        MakeObjectFunctionFactory factory = new MakeObjectFunctionFactory();

        Expression nameExpression = MapperTestUtils.expressionReturningWithWrapping(expressionContext, "customerId");

        Expression expression = factory.create(Arrays.asList(nameExpression, null));
        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(ComplexField.class, result);

        ComplexField complex = (ComplexField) result;
        List<Field> children = getChildren(complex);

        assertEquals(1, children.size());

        Field child = children.getFirst();
        assertInstanceOf(SimpleField.class, child);
        assertEquals("/result/customerId", child.getPath());
        assertEquals("customerId", child.getName());
        assertNull(child.getValue());
    }

    @Test
    void shouldSkipEntryWhenNameExpressionIsNull() throws Exception {
        MakeObjectFunctionFactory factory = new MakeObjectFunctionFactory();

        Expression valueExpression =
                MapperTestUtils.expressionReturningField(expressionContext, MapperTestUtils.sourceField("/source/id", "sourceName", "123", CollectionType.NONE));

        Expression expression = factory.create(Arrays.asList(null, valueExpression));
        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(ComplexField.class, result);

        ComplexField complex = (ComplexField) result;
        assertTrue(getChildren(complex).isEmpty());
    }

    @Test
    void shouldSkipEntryWhenNameFieldValueIsNull() throws Exception {
        MakeObjectFunctionFactory factory = new MakeObjectFunctionFactory();

        Expression nameExpression =
                MapperTestUtils.expressionReturningField(expressionContext, MapperTestUtils.sourceField("/source/name", "name", null, CollectionType.NONE));
        Expression valueExpression =
                MapperTestUtils.expressionReturningField(expressionContext, MapperTestUtils.sourceField("/source/id", "sourceName", "123", CollectionType.NONE));

        Expression expression = factory.create(List.of(nameExpression, valueExpression));
        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(ComplexField.class, result);

        ComplexField complex = (ComplexField) result;
        assertTrue(getChildren(complex).isEmpty());
    }
}
