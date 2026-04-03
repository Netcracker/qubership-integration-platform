package org.qubership.integration.platform.engine.mapper.atlasmap.expressions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.ExpressionException;
import io.atlasmap.expression.internal.BooleanExpression;
import io.atlasmap.expression.internal.VariableExpression;
import io.atlasmap.v2.Field;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static io.atlasmap.v2.AtlasModelFactory.wrapWithField;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class UnaryExpressionTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldNegateLong() throws Exception {
        Expression expression = UnaryExpression.createNegate(expressionReturningValue(10L));

        Field result = expression.evaluate(expressionContext);

        assertEquals(-10L, result.getValue());
    }

    @Test
    void shouldNegateFloat() throws Exception {
        Expression expression = UnaryExpression.createNegate(expressionReturningValue(1.5f));

        Field result = expression.evaluate(expressionContext);

        assertEquals(-1.5f, result.getValue());
    }

    @Test
    void shouldNegateDouble() throws Exception {
        Expression expression = UnaryExpression.createNegate(expressionReturningValue(2.5d));

        Field result = expression.evaluate(expressionContext);

        assertEquals(-2.5d, result.getValue());
    }

    @Test
    void shouldNegateInteger() throws Exception {
        Expression expression = UnaryExpression.createNegate(expressionReturningValue(10));

        Field result = expression.evaluate(expressionContext);

        assertEquals(-10, result.getValue());
    }

    @Test
    void shouldNegateBigInteger() throws Exception {
        Expression expression = UnaryExpression.createNegate(expressionReturningValue(BigInteger.valueOf(15)));

        Field result = expression.evaluate(expressionContext);

        assertEquals(BigInteger.valueOf(-15), result.getValue());
    }

    @Test
    void shouldNegateBigDecimal() throws Exception {
        Expression expression = UnaryExpression.createNegate(expressionReturningValue(BigDecimal.valueOf(12.5)));

        Field result = expression.evaluate(expressionContext);

        assertEquals(BigDecimal.valueOf(-12.5), result.getValue());
    }

    @Test
    void shouldConvertNegatedBigDecimalRepresentingLongMinValueToLong() throws Exception {
        BigDecimal value = BigDecimal.valueOf(Long.MIN_VALUE).negate();
        Expression expression = UnaryExpression.createNegate(expressionReturningValue(value));

        Field result = expression.evaluate(expressionContext);

        assertEquals(Long.MIN_VALUE, result.getValue());
    }

    @Test
    void shouldReturnNullFieldWhenNegatingNull() throws Exception {
        Expression expression = UnaryExpression.createNegate(expressionReturningValue(null));

        Field result = expression.evaluate(expressionContext);

        assertNotNull(result);
        assertNull(result.getValue());
    }

    @Test
    void shouldReturnNullWhenNegatingNonNumber() throws Exception {
        Expression expression = UnaryExpression.createNegate(expressionReturningValue("abc"));

        assertNull(expression.evaluate(expressionContext));
    }

    @Test
    void shouldReturnTrueWhenValueIsInList() throws Exception {
        VariableExpression variableExpression = new VariableExpression("name");
        List<Object> elements = List.of("A", "B", "C");

        when(expressionContext.getVariable("name")).thenReturn(wrapWithField("B"));

        BooleanExpression expression = UnaryExpression.createInExpression(variableExpression, elements, false);

        assertBooleanResult(expression, true);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnFalseWhenValueIsNotInList() throws Exception {
        VariableExpression variableExpression = new VariableExpression("name");
        List<Object> elements = List.of("A", "B", "C");

        when(expressionContext.getVariable("name")).thenReturn(wrapWithField("Z"));

        BooleanExpression expression = UnaryExpression.createInExpression(variableExpression, elements, false);

        assertBooleanResult(expression, false);
        assertFalse(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnTrueWhenValueIsNotInListAndNotFlagEnabled() throws Exception {
        VariableExpression variableExpression = new VariableExpression("name");
        List<Object> elements = List.of("A", "B", "C");

        when(expressionContext.getVariable("name")).thenReturn(wrapWithField("Z"));

        BooleanExpression expression = UnaryExpression.createInExpression(variableExpression, elements, true);

        assertBooleanResult(expression, true);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnFalseWhenValueCheckedAgainstEmptyInList() throws Exception {
        VariableExpression variableExpression = new VariableExpression("name");

        when(expressionContext.getVariable("name")).thenReturn(wrapWithField("A"));

        BooleanExpression expression = UnaryExpression.createInExpression(variableExpression, List.of(), false);

        assertBooleanResult(expression, false);
    }

    @Test
    void shouldReturnNullWhenInExpressionValueIsNull() throws Exception {
        VariableExpression variableExpression = new VariableExpression("name");
        List<Object> elements = List.of("A", "B");

        when(expressionContext.getVariable("name")).thenReturn(wrapWithField(null));

        BooleanExpression expression = UnaryExpression.createInExpression(variableExpression, elements, false);

        Field result = expression.evaluate(expressionContext);

        assertNotNull(result);
        assertNull(result.getValue());
    }

    @Test
    void shouldReturnNullWhenInExpressionValueIsNotString() throws Exception {
        VariableExpression variableExpression = new VariableExpression("name");
        List<Object> elements = List.of("A", "B");

        when(expressionContext.getVariable("name")).thenReturn(wrapWithField(10));

        BooleanExpression expression = UnaryExpression.createInExpression(variableExpression, elements, false);

        Field result = expression.evaluate(expressionContext);

        assertNotNull(result);
        assertNull(result.getValue());
    }

    @Test
    void shouldRenderInExpressionToString() {
        VariableExpression variableExpression = new VariableExpression("name");
        List<Object> elements = List.of("A", "B");

        BooleanExpression expression = UnaryExpression.createInExpression(variableExpression, elements, false);

        assertEquals("${name} IN ( A, B )", expression.toString());
    }

    @Test
    void shouldRenderNotInExpressionToString() {
        VariableExpression variableExpression = new VariableExpression("name");
        List<Object> elements = List.of("A", "B");

        BooleanExpression expression = UnaryExpression.createInExpression(variableExpression, elements, true);

        assertEquals("${name} NOT IN ( A, B )", expression.toString());
    }

    @Test
    void shouldThrowNullPointerExceptionWhenInExpressionToStringCalledWithEmptyListCurrentBehavior() {
        VariableExpression variableExpression = new VariableExpression("name");

        BooleanExpression expression = UnaryExpression.createInExpression(variableExpression, List.of(), false);

        assertThrows(NullPointerException.class, expression::toString);
    }

    @Test
    void shouldThrowExceptionWhenNegatingUnsupportedNumberType() throws Exception {
        Expression expression = UnaryExpression.createNegate(expressionReturningValue(new UnaryExpressionTest.UnsupportedNumber(7)));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> expression.evaluate(expressionContext));

        assertTrue(exception.getMessage().contains("Don't know how to negate"));
    }

    @Test
    void shouldInvertBooleanTrueInNotExpression() throws Exception {
        BooleanExpression expression = UnaryExpression.createNOT(booleanExpressionReturning(Boolean.TRUE));

        assertBooleanResult(expression, false);
        assertFalse(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnNullFieldWhenNotExpressionOperandIsNull() throws Exception {
        BooleanExpression expression = UnaryExpression.createNOT(booleanExpressionReturning(null));

        Field result = expression.evaluate(expressionContext);

        assertNotNull(result);
        assertNull(result.getValue());
        assertFalse(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnTrueWhenBooleanCastOperandIsTrue() throws Exception {
        BooleanExpression expression = UnaryExpression.createBooleanCast(expressionReturningValue(Boolean.TRUE));

        assertBooleanResult(expression, true);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnTrueWhenValueIsInLargeListUsingHashSetBranch() throws Exception {
        VariableExpression variableExpression = new VariableExpression("name");
        when(expressionContext.getVariable("name")).thenReturn(wrapWithField("E"));

        BooleanExpression expression = UnaryExpression.createInExpression(
                variableExpression,
                List.of("A", "B", "C", "D", "E"),
                false
        );

        Field result = expression.evaluate(expressionContext);

        assertEquals(Boolean.TRUE, result.getValue());
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnFalseWhenBooleanCastOperandIsNotBoolean() throws Exception {
        BooleanExpression expression = UnaryExpression.createBooleanCast(expressionReturningValue("true"));

        assertBooleanResult(expression, false);
        assertFalse(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnFalseWhenValueIsInListAndNotFlagEnabled() throws Exception {
        VariableExpression variableExpression = new VariableExpression("name");
        when(expressionContext.getVariable("name")).thenReturn(wrapWithField("B"));

        BooleanExpression expression = UnaryExpression.createInExpression(
                variableExpression,
                List.of("A", "B", "C"),
                true
        );

        Field result = expression.evaluate(expressionContext);

        assertEquals(Boolean.FALSE, result.getValue());
        assertFalse(expression.matches(expressionContext));
    }

    @Test
    void shouldInvertBooleanFalseInNotExpression() throws Exception {
        BooleanExpression expression = UnaryExpression.createNOT(booleanExpressionReturning(Boolean.FALSE));

        Field result = expression.evaluate(expressionContext);

        assertEquals(Boolean.TRUE, result.getValue());
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnFalseWhenBooleanCastOperandIsFalse() throws Exception {
        BooleanExpression expression = UnaryExpression.createBooleanCast(expressionReturningValue(Boolean.FALSE));

        Field result = expression.evaluate(expressionContext);

        assertEquals(Boolean.FALSE, result.getValue());
        assertFalse(expression.matches(expressionContext));
    }

    @Test
    void shouldGetAndSetRightExpression() {
        Expression original = dummyExpression("left");
        Expression replacement = dummyExpression("right");

        UnaryExpression expression = (UnaryExpression) UnaryExpression.createNegate(original);

        assertSame(original, expression.getRight());

        expression.setRight(replacement);

        assertSame(replacement, expression.getRight());
    }

    @Test
    void shouldUseStringRepresentationForHashCodeAndEquals() {
        Expression operand = dummyExpression("value");

        UnaryExpression first = (UnaryExpression) UnaryExpression.createNegate(operand);
        UnaryExpression second = (UnaryExpression) UnaryExpression.createNegate(dummyExpression("value"));
        UnaryExpression third = (UnaryExpression) UnaryExpression.createNegate(dummyExpression("other"));

        assertEquals("(- value)", first.toString());
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first, second);
        assertNotEquals(first, third);
        assertNotEquals(first, null);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenBooleanCastMatchesEvaluatesNullCurrentBehavior() throws Exception {
        BooleanExpression expression = UnaryExpression.createBooleanCast(expressionReturningValue(null));

        assertThrows(NullPointerException.class, () -> expression.matches(expressionContext));
    }

    private void assertBooleanResult(BooleanExpression expression, boolean expected) throws ExpressionException {
        Field result = expression.evaluate(expressionContext);

        assertNotNull(result);
        assertEquals(expected, result.getValue());
    }

    private Expression expressionReturningValue(Object value) throws ExpressionException {
        Expression expression = mock(Expression.class);
        when(expression.evaluate(expressionContext)).thenReturn(wrapWithField(value));
        return expression;
    }

    private BooleanExpression booleanExpressionReturning(Boolean value) throws ExpressionException {
        BooleanExpression expression = mock(BooleanExpression.class);
        when(expression.evaluate(expressionContext)).thenReturn(wrapWithField(value));
        return expression;
    }

    private Expression dummyExpression(String text) {
        return new Expression() {
            @Override
            public Field evaluate(ExpressionContext expressionContext) {
                throw new UnsupportedOperationException("evaluate should not be called");
            }

            @Override
            public String toString() {
                return text;
            }
        };
    }

    private static class UnsupportedNumber extends Number {
        private final int value;

        private UnsupportedNumber(int value) {
            this.value = value;
        }

        @Override
        public int intValue() {
            return value;
        }

        @Override
        public long longValue() {
            return value;
        }

        @Override
        public float floatValue() {
            return value;
        }

        @Override
        public double doubleValue() {
            return value;
        }
    }
}
