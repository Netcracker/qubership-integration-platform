package org.qubership.integration.platform.engine.mapper.atlasmap.functions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.ExpressionException;
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

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SortFunctionFactoryTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldReturnSortName() {
        SortFunctionFactory factory = new SortFunctionFactory();

        assertEquals("sort", factory.getName());
    }

    @Test
    void shouldThrowParseExceptionWhenArgumentsCountIsNotTwo() {
        SortFunctionFactory factory = new SortFunctionFactory();

        ParseException exception = assertThrows(ParseException.class,
                () -> factory.create(List.of(mock(Expression.class))));

        assertEquals("sort function expects 2 arguments.", exception.getMessage());
    }

    @Test
    void shouldSortCollectionByScalarIntegerKeysAscending() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>");

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", FieldType.INTEGER, 3);
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", FieldType.INTEGER, 1);
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", FieldType.INTEGER, 2);

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            assertInstanceOf(FieldGroup.class, result);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals(FieldType.ANY, sorted.getFieldType());
            assertEquals(3, sorted.getField().size());
            assertEquals(1, sorted.getField().get(0).getValue());
            assertEquals(2, sorted.getField().get(1).getValue());
            assertEquals(3, sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldSortCollectionByInferredStringTypeWhenFieldTypeIsNull() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>");

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", null, "c");
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", null, "a");
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", null, "b");

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals("a", sorted.getField().get(0).getValue());
            assertEquals("b", sorted.getField().get(1).getValue());
            assertEquals("c", sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldPlaceElementsWithNullSortingKeyAtEnd() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);

        Expression sortingKeyExpression = ctx -> {
            Field current = ctx.getVariable("doc1:/items<>");
            return current.getValue() == null ? null : current;
        };

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", FieldType.INTEGER, 2);
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", FieldType.INTEGER, null);
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", FieldType.INTEGER, 1);

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals(1, sorted.getField().get(0).getValue());
            assertEquals(2, sorted.getField().get(1).getValue());
            assertNull(sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldPlaceElementsWithSortingExpressionExceptionAtEnd() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);

        Expression sortingKeyExpression = ctx -> {
            Field current = ctx.getVariable("doc1:/items<>");
            if ("bad".equals(current.getValue())) {
                throw new ExpressionException("boom");
            }
            return current;
        };

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", FieldType.STRING, "c");
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", FieldType.STRING, "bad");
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", FieldType.STRING, "a");

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals("a", sorted.getField().get(0).getValue());
            assertEquals("c", sorted.getField().get(1).getValue());
            assertEquals("bad", sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldSortByNestedCollectionKeys() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>/key<>");

        FieldGroup itemOne = MapperTestUtils.itemWithCollectionKey("/items<0>", "itemOne", 2, 3);
        FieldGroup itemTwo = MapperTestUtils.itemWithCollectionKey("/items<1>", "itemTwo", 1, 9);
        FieldGroup itemThree = MapperTestUtils.itemWithCollectionKey("/items<2>", "itemThree", 2, 1);

        FieldGroup sourceGroup = MapperTestUtils.collection(itemOne, itemTwo, itemThree);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertSame(itemTwo, sorted.getField().get(0));
            assertSame(itemThree, sorted.getField().get(1));
            assertSame(itemOne, sorted.getField().get(2));
        }
    }

    @Test
    void shouldSortCollectionByCharacterKeys() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>");

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", FieldType.CHAR, 'c');
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", FieldType.CHAR, 'a');
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", FieldType.CHAR, 'b');

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals('a', sorted.getField().get(0).getValue());
            assertEquals('b', sorted.getField().get(1).getValue());
            assertEquals('c', sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldSortCollectionByBooleanKeys() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>");

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", FieldType.BOOLEAN, true);
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", FieldType.BOOLEAN, false);
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", FieldType.BOOLEAN, true);

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals(false, sorted.getField().get(0).getValue());
            assertEquals(true, sorted.getField().get(1).getValue());
            assertEquals(true, sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldSortCollectionByShortKeys() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>");

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", FieldType.SHORT, (short) 30);
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", FieldType.SHORT, (short) 10);
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", FieldType.SHORT, (short) 20);

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals((short) 10, sorted.getField().get(0).getValue());
            assertEquals((short) 20, sorted.getField().get(1).getValue());
            assertEquals((short) 30, sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldSortCollectionByLongKeys() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>");

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", FieldType.LONG, 30L);
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", FieldType.LONG, 10L);
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", FieldType.LONG, 20L);

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals(10L, sorted.getField().get(0).getValue());
            assertEquals(20L, sorted.getField().get(1).getValue());
            assertEquals(30L, sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldSortCollectionByByteKeys() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>");

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", FieldType.BYTE, (byte) 3);
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", FieldType.BYTE, (byte) 1);
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", FieldType.BYTE, (byte) 2);

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals((byte) 1, sorted.getField().get(0).getValue());
            assertEquals((byte) 2, sorted.getField().get(1).getValue());
            assertEquals((byte) 3, sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldSortCollectionByBigIntegerKeys() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>");

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", FieldType.BIG_INTEGER, new BigInteger("30"));
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", FieldType.BIG_INTEGER, new BigInteger("10"));
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", FieldType.BIG_INTEGER, new BigInteger("20"));

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals(new BigInteger("10"), sorted.getField().get(0).getValue());
            assertEquals(new BigInteger("20"), sorted.getField().get(1).getValue());
            assertEquals(new BigInteger("30"), sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldSortCollectionByDoubleKeys() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>");

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", FieldType.DOUBLE, 3.3d);
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", FieldType.DOUBLE, 1.1d);
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", FieldType.DOUBLE, 2.2d);

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals(1.1d, sorted.getField().get(0).getValue());
            assertEquals(2.2d, sorted.getField().get(1).getValue());
            assertEquals(3.3d, sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldSortCollectionByFloatKeys() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>");

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", FieldType.FLOAT, 3.3f);
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", FieldType.FLOAT, 1.1f);
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", FieldType.FLOAT, 2.2f);

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals(1.1f, sorted.getField().get(0).getValue());
            assertEquals(2.2f, sorted.getField().get(1).getValue());
            assertEquals(3.3f, sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldSortCollectionByNumberKeysWhenFieldTypeIsNumber() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>");

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", FieldType.NUMBER, 3.3d);
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", FieldType.NUMBER, 1.1f);
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", FieldType.NUMBER, 2.2d);

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals(1.1f, sorted.getField().get(0).getValue());
            assertEquals(2.2d, sorted.getField().get(1).getValue());
            assertEquals(3.3d, sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldSortCollectionByRuntimeShortTypeWhenFieldTypeIsNull() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>");

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", null, (short) 30);
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", null, (short) 10);
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", null, (short) 20);

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals((short) 10, sorted.getField().get(0).getValue());
            assertEquals((short) 20, sorted.getField().get(1).getValue());
            assertEquals((short) 30, sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldSortCollectionByRuntimeLongTypeWhenFieldTypeIsNull() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>");

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", null, 30L);
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", null, 10L);
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", null, 20L);

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals(10L, sorted.getField().get(0).getValue());
            assertEquals(20L, sorted.getField().get(1).getValue());
            assertEquals(30L, sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldSortCollectionByRuntimeByteTypeWhenFieldTypeIsNull() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>");

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", null, (byte) 3);
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", null, (byte) 1);
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", null, (byte) 2);

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals((byte) 1, sorted.getField().get(0).getValue());
            assertEquals((byte) 2, sorted.getField().get(1).getValue());
            assertEquals((byte) 3, sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldSortCollectionByRuntimeBigIntegerTypeWhenFieldTypeIsNull() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>");

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", null, new BigInteger("30"));
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", null, new BigInteger("10"));
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", null, new BigInteger("20"));

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals(new BigInteger("10"), sorted.getField().get(0).getValue());
            assertEquals(new BigInteger("20"), sorted.getField().get(1).getValue());
            assertEquals(new BigInteger("30"), sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldSortCollectionByRuntimeCharacterTypeWhenFieldTypeIsNull() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>");

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", null, 'c');
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", null, 'a');
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", null, 'b');

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals('a', sorted.getField().get(0).getValue());
            assertEquals('b', sorted.getField().get(1).getValue());
            assertEquals('c', sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldSortCollectionByRuntimeBooleanTypeWhenFieldTypeIsNull() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>");

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", null, true);
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", null, false);
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", null, true);

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals(false, sorted.getField().get(0).getValue());
            assertEquals(true, sorted.getField().get(1).getValue());
            assertEquals(true, sorted.getField().get(2).getValue());
        }
    }

    @Test
    void shouldSortCollectionByDefaultComparatorWhenFieldTypeIsNone() throws Exception {
        SortFunctionFactory factory = new SortFunctionFactory();
        Expression collectionExpression = mock(Expression.class);
        Expression sortingKeyExpression = currentItemExpression("doc1:/items<>");

        SimpleField first = MapperTestUtils.simpleField("/items<0>", "first", FieldType.NONE, new CustomValue("c"));
        SimpleField second = MapperTestUtils.simpleField("/items<1>", "second", FieldType.NONE, new CustomValue("a"));
        SimpleField third = MapperTestUtils.simpleField("/items<2>", "third", FieldType.NONE, new CustomValue("b"));

        FieldGroup sourceGroup = MapperTestUtils.collection(first, second, third);
        when(collectionExpression.evaluate(expressionContext)).thenReturn(sourceGroup);

        try (MockedStatic<FieldUtils> fieldUtils = org.mockito.Mockito.mockStatic(FieldUtils.class, CALLS_REAL_METHODS)) {
            fieldUtils.when(() -> FieldUtils.replacePathPrefix(any(Field.class), any(), any()))
                    .thenAnswer(invocation -> null);

            Expression expression = factory.create(List.of(collectionExpression, sortingKeyExpression));
            Field result = expression.evaluate(expressionContext);

            FieldGroup sorted = (FieldGroup) result;
            assertEquals("a", sorted.getField().get(0).getValue().toString());
            assertEquals("b", sorted.getField().get(1).getValue().toString());
            assertEquals("c", sorted.getField().get(2).getValue().toString());
        }
    }

    private Expression currentItemExpression(String variableName) {
        return ctx -> ctx.getVariable(variableName);
    }

    private record CustomValue(String value) {

        @Override
            public String toString() {
                return value;
            }
        }
}
