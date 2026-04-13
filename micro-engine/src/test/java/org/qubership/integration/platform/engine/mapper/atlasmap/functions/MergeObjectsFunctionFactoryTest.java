package org.qubership.integration.platform.engine.mapper.atlasmap.functions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.v2.CollectionType;
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
import org.qubership.integration.platform.mapper.ComplexField;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MergeObjectsFunctionFactoryTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldReturnMergeObjectsName() {
        MergeObjectsFunctionFactory factory = new MergeObjectsFunctionFactory();

        assertEquals("mergeObjects", factory.getName());
    }

    @Test
    void shouldReturnEmptyComplexFieldWhenArgumentsAreEmpty() throws Exception {
        MergeObjectsFunctionFactory factory = new MergeObjectsFunctionFactory();

        Expression expression = factory.create(List.of());
        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(ComplexField.class, result);

        ComplexField complex = (ComplexField) result;
        assertEquals(FieldType.COMPLEX, complex.getFieldType());
        assertEquals(CollectionType.NONE, complex.getCollectionType());
        assertEquals("/result", complex.getPath());
        assertEquals("result", complex.getName());
        assertTrue(complex.getChildFields().isEmpty());
    }

    @Test
    void shouldSkipNullExpressionAndNullEvaluatedField() throws Exception {
        MergeObjectsFunctionFactory factory = new MergeObjectsFunctionFactory();
        Expression nullFieldExpression = mock(Expression.class);

        when(nullFieldExpression.evaluate(expressionContext)).thenReturn(null);

        Expression expression = factory.create(Arrays.asList(null, nullFieldExpression));
        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(ComplexField.class, result);

        ComplexField complex = (ComplexField) result;
        assertTrue(complex.getChildFields().isEmpty());
    }

    @Test
    void shouldMergeComplexFieldChildren() throws Exception {
        MergeObjectsFunctionFactory factory = new MergeObjectsFunctionFactory();
        Expression sourceExpression = mock(Expression.class);

        SimpleField child1 = MapperTestUtils.simpleFieldWithName("/source/id", "id", "1");
        SimpleField child2 = MapperTestUtils.simpleFieldWithName("/source/name", "name", "Bob");

        ComplexField source = new ComplexField(List.of(child1, child2));
        source.setPath("/source");
        source.setName("source");

        ComplexField cloned = new ComplexField(List.of(child1, child2));
        cloned.setPath("/source");
        cloned.setName("source");

        when(sourceExpression.evaluate(expressionContext)).thenReturn(source);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class)) {
            fieldUtils.when(() -> FieldUtils.cloneField(any(Field.class))).thenReturn(cloned);
            fieldUtils.when(() -> FieldUtils.replacePathSegments(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(sourceExpression));
            Field result = expression.evaluate(expressionContext);

            assertInstanceOf(ComplexField.class, result);

            ComplexField merged = (ComplexField) result;
            assertEquals(2, merged.getChildFields().size());
            assertSame(child1, merged.getChildFields().get(0));
            assertSame(child2, merged.getChildFields().get(1));
        }
    }

    @Test
    void shouldMergeOnlyComplexChildrenFromFieldGroup() throws Exception {
        MergeObjectsFunctionFactory factory = new MergeObjectsFunctionFactory();
        Expression sourceExpression = mock(Expression.class);

        SimpleField simpleChild = MapperTestUtils.simpleFieldWithName("/source/simple", "simple", "x");

        SimpleField nestedChild = MapperTestUtils.simpleFieldWithName("/source/nested/id", "id", "1");
        ComplexField nestedComplex = new ComplexField(List.of(nestedChild));
        nestedComplex.setPath("/source/nested");
        nestedComplex.setName("nested");

        FieldGroup sourceGroup = new FieldGroup();
        sourceGroup.setPath("/source");
        sourceGroup.getField().add(simpleChild);
        sourceGroup.getField().add(nestedComplex);

        FieldGroup clonedGroup = new FieldGroup();
        clonedGroup.setPath("/source");
        clonedGroup.getField().add(simpleChild);
        clonedGroup.getField().add(nestedComplex);

        when(sourceExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class)) {
            fieldUtils.when(() -> FieldUtils.cloneField(any(Field.class))).thenReturn(clonedGroup);
            fieldUtils.when(() -> FieldUtils.replacePathSegments(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(sourceExpression));
            Field result = expression.evaluate(expressionContext);

            assertInstanceOf(ComplexField.class, result);

            ComplexField merged = (ComplexField) result;
            assertEquals(1, merged.getChildFields().size());
            assertSame(nestedComplex, merged.getChildFields().get(0));
        }
    }

    @Test
    void shouldIgnoreSimpleFieldInput() throws Exception {
        MergeObjectsFunctionFactory factory = new MergeObjectsFunctionFactory();
        Expression sourceExpression = mock(Expression.class);

        SimpleField source = MapperTestUtils.simpleFieldWithName("/source/value", "value", "A");
        SimpleField cloned = MapperTestUtils.simpleFieldWithName("/source/value", "value", "A");

        when(sourceExpression.evaluate(expressionContext)).thenReturn(source);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class)) {
            fieldUtils.when(() -> FieldUtils.cloneField(any(Field.class))).thenReturn(cloned);
            fieldUtils.when(() -> FieldUtils.replacePathSegments(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(sourceExpression));
            Field result = expression.evaluate(expressionContext);

            assertInstanceOf(ComplexField.class, result);

            ComplexField merged = (ComplexField) result;
            assertTrue(merged.getChildFields().isEmpty());
        }
    }

    @Test
    void shouldMergeMultipleInputsInOrder() throws Exception {
        MergeObjectsFunctionFactory factory = new MergeObjectsFunctionFactory();
        Expression firstExpression = mock(Expression.class);
        Expression secondExpression = mock(Expression.class);

        SimpleField firstChild = MapperTestUtils.simpleFieldWithName("/first/id", "id", "1");
        ComplexField firstSource = new ComplexField(List.of(firstChild));
        firstSource.setPath("/first");

        SimpleField secondChild = MapperTestUtils.simpleFieldWithName("/second/name", "name", "Bob");
        ComplexField secondSource = new ComplexField(List.of(secondChild));
        secondSource.setPath("/second");

        ComplexField firstClone = new ComplexField(List.of(firstChild));
        firstClone.setPath("/first");

        ComplexField secondClone = new ComplexField(List.of(secondChild));
        secondClone.setPath("/second");

        when(firstExpression.evaluate(expressionContext)).thenReturn(firstSource);
        when(secondExpression.evaluate(expressionContext)).thenReturn(secondSource);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class)) {
            fieldUtils.when(() -> FieldUtils.cloneField(firstSource)).thenReturn(firstClone);
            fieldUtils.when(() -> FieldUtils.cloneField(secondSource)).thenReturn(secondClone);
            fieldUtils.when(() -> FieldUtils.replacePathSegments(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(firstExpression, secondExpression));
            Field result = expression.evaluate(expressionContext);

            assertInstanceOf(ComplexField.class, result);

            ComplexField merged = (ComplexField) result;
            assertEquals(2, merged.getChildFields().size());
            assertSame(firstChild, merged.getChildFields().get(0));
            assertSame(secondChild, merged.getChildFields().get(1));
        }
    }
}
