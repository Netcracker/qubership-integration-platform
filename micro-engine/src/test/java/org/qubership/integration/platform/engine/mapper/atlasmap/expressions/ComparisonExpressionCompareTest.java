package org.qubership.integration.platform.engine.mapper.atlasmap.expressions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.ExpressionException;
import io.atlasmap.expression.internal.BooleanExpression;
import io.atlasmap.v2.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.math.BigDecimal;
import java.math.BigInteger;

import static io.atlasmap.v2.AtlasModelFactory.wrapWithField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ComparisonExpressionCompareTest {

    @Mock
    ExpressionContext expressionContext;

    @AfterEach
    void tearDown() {
        ComparisonExpression.CONVERT_STRING_EXPRESSIONS.remove();
    }

    @Test
    void shouldReturnTrueWhenIntegerComparedToStringAndStringConversionEnabled() throws Exception {
        ComparisonExpression.CONVERT_STRING_EXPRESSIONS.set(Boolean.TRUE);

        BooleanExpression expression = ComparisonExpression.createEqual(
                expressionReturningValue(10),
                expressionReturningValue("10")
        );

        assertBooleanResult(expression, true);
    }

    @Test
    void shouldReturnFalseWhenIntegerComparedToStringAndStringConversionDisabled() throws Exception {
        BooleanExpression expression = ComparisonExpression.createEqual(
                expressionReturningValue(10),
                expressionReturningValue("10")
        );

        assertBooleanResult(expression, false);
    }

    @Test
    void shouldStillEnableStringConversionWhenThreadLocalContainsFalseCurrentBehavior() throws Exception {
        ComparisonExpression.CONVERT_STRING_EXPRESSIONS.set(Boolean.FALSE);

        BooleanExpression expression = ComparisonExpression.createEqual(
                expressionReturningValue(10),
                expressionReturningValue("10")
        );

        assertBooleanResult(expression, true);
    }

    @Test
    void shouldReturnFalseWhenStringCannotBeConvertedToInteger() throws Exception {
        ComparisonExpression.CONVERT_STRING_EXPRESSIONS.set(Boolean.TRUE);

        BooleanExpression expression = ComparisonExpression.createEqual(
                expressionReturningValue(10),
                expressionReturningValue("abc")
        );

        assertBooleanResult(expression, false);
    }

    @Test
    void shouldReturnFalseForIncompatibleTypes() {
        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.FALSE, expression.compareValues(Boolean.TRUE, 1));
    }

    @Test
    void shouldReturnTrueWhenByteComparedToBigDecimalWithSameValue() {
        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.TRUE, expression.compareValues((byte) 5, BigDecimal.valueOf(5)));
    }

    @Test
    void shouldReturnTrueWhenFloatComparedToBigIntegerWithSameValue() {
        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.TRUE, expression.compareValues(7.0f, BigInteger.valueOf(7)));
    }

    @Test
    void shouldReturnTrueWhenStringComparedToBigDecimalWithSameValueAndStringConversionEnabled() {
        ComparisonExpression.CONVERT_STRING_EXPRESSIONS.set(Boolean.TRUE);

        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.TRUE, expression.compareValues("12.5", BigDecimal.valueOf(12.5)));
    }

    @Test
    void shouldReturnFalseWhenStringComparedToBigDecimalWithSameValueAndStringConversionDisabled() {
        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.FALSE, expression.compareValues("12.5", BigDecimal.valueOf(12.5)));
    }

    @Test
    void shouldReturnNullWhenLeftOperandEvaluatesToNull() throws Exception {
        TestComparisonExpression expression = new TestComparisonExpression(
                expressionReturningValue(null),
                dummyExpression()
        );

        Field result = expression.evaluate(expressionContext);

        assertNull(result.getValue());
    }

    @Test
    void shouldReturnNullWhenRightOperandEvaluatesToNull() throws Exception {
        TestComparisonExpression expression = new TestComparisonExpression(
                expressionReturningValue(10),
                expressionReturningValue(null)
        );

        Field result = expression.evaluate(expressionContext);

        assertNull(result.getValue());
    }

    @Test
    void shouldReturnTrueWhenLeftOperandIsGreaterThanRightOperand() throws Exception {
        BooleanExpression expression = ComparisonExpression.createGreaterThan(
                expressionReturningValue(20),
                expressionReturningValue(10)
        );

        assertBooleanResult(expression, true);
    }

    @Test
    void shouldReturnTrueWhenLeftOperandIsLessThanOrEqualToRightOperand() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLessThanEqual(
                expressionReturningValue(10),
                expressionReturningValue(10)
        );

        assertBooleanResult(expression, true);
    }

    @Test
    void shouldReturnTrueWhenBooleanComparedToStringAndStringConversionEnabled() {
        ComparisonExpression.CONVERT_STRING_EXPRESSIONS.set(Boolean.TRUE);

        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.TRUE, expression.compareValues(Boolean.TRUE, "true"));
    }

    @Test
    void shouldReturnFalseWhenBooleanComparedToStringAndStringConversionDisabled() {
        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.FALSE, expression.compareValues(Boolean.TRUE, "true"));
    }

    @Test
    void shouldReturnTrueWhenShortComparedToBigDecimalWithSameValue() {
        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.TRUE, expression.compareValues((short) 4, BigDecimal.valueOf(4)));
    }

    @Test
    void shouldReturnTrueWhenIntegerComparedToBigIntegerWithSameValue() {
        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.TRUE, expression.compareValues(9, BigInteger.valueOf(9)));
    }

    @Test
    void shouldReturnTrueWhenLongComparedToIntegerWithSameValue() {
        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.TRUE, expression.compareValues(11L, 11));
    }

    @Test
    void shouldReturnTrueWhenDoubleComparedToFloatWithSameValue() {
        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.TRUE, expression.compareValues(8.0, 8.0f));
    }

    @Test
    void shouldReturnTrueWhenBigIntegerComparedToDoubleWithSameValue() {
        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.TRUE, expression.compareValues(BigInteger.valueOf(6), 6.0));
    }

    @Test
    void shouldReturnTrueWhenBigDecimalComparedToBigIntegerWithSameValue() {
        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.TRUE, expression.compareValues(BigDecimal.valueOf(13), BigInteger.valueOf(13)));
    }

    @Test
    void shouldReturnFalseWhenBigIntegerComparedToUnsupportedType() {
        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.FALSE, expression.compareValues(BigInteger.valueOf(7), Boolean.TRUE));
    }

    @Test
    void shouldReturnFalseWhenBigDecimalComparedToUnsupportedType() {
        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.FALSE, expression.compareValues(BigDecimal.valueOf(7), Boolean.TRUE));
    }

    @Test
    void shouldCompareIntegralTypesAcrossSupportedConversions() {
        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.TRUE, expression.compareValues((byte) 3, (short) 3));
        assertEquals(Boolean.TRUE, expression.compareValues((byte) 3, 3));
        assertEquals(Boolean.TRUE, expression.compareValues((byte) 3, 3L));
        assertEquals(Boolean.TRUE, expression.compareValues((byte) 3, 3.0f));
        assertEquals(Boolean.TRUE, expression.compareValues((byte) 3, 3.0));
        assertEquals(Boolean.TRUE, expression.compareValues((byte) 3, BigInteger.valueOf(3)));
        assertEquals(Boolean.TRUE, expression.compareValues((byte) 3, BigDecimal.valueOf(3)));

        assertEquals(Boolean.TRUE, expression.compareValues((short) 4, 4));
        assertEquals(Boolean.TRUE, expression.compareValues((short) 4, 4L));
        assertEquals(Boolean.TRUE, expression.compareValues((short) 4, 4.0f));
        assertEquals(Boolean.TRUE, expression.compareValues((short) 4, 4.0));
        assertEquals(Boolean.TRUE, expression.compareValues((short) 4, BigInteger.valueOf(4)));
        assertEquals(Boolean.TRUE, expression.compareValues((short) 4, BigDecimal.valueOf(4)));

        assertEquals(Boolean.TRUE, expression.compareValues(5, 5L));
        assertEquals(Boolean.TRUE, expression.compareValues(5, 5.0f));
        assertEquals(Boolean.TRUE, expression.compareValues(5, 5.0));
        assertEquals(Boolean.TRUE, expression.compareValues(5, BigInteger.valueOf(5)));
        assertEquals(Boolean.TRUE, expression.compareValues(5, BigDecimal.valueOf(5)));

        assertEquals(Boolean.TRUE, expression.compareValues(6L, 6));
        assertEquals(Boolean.TRUE, expression.compareValues(6L, 6.0f));
        assertEquals(Boolean.TRUE, expression.compareValues(6L, 6.0));
        assertEquals(Boolean.TRUE, expression.compareValues(6L, BigInteger.valueOf(6)));
        assertEquals(Boolean.TRUE, expression.compareValues(6L, BigDecimal.valueOf(6)));
    }

    @Test
    void shouldCompareFloatingPointAndBigNumberTypesAcrossSupportedConversions() {
        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.TRUE, expression.compareValues(7.0f, 7));
        assertEquals(Boolean.TRUE, expression.compareValues(7.0f, 7L));
        assertEquals(Boolean.TRUE, expression.compareValues(7.0f, 7.0));
        assertEquals(Boolean.TRUE, expression.compareValues(7.0f, BigInteger.valueOf(7)));
        assertEquals(Boolean.TRUE, expression.compareValues(7.0f, BigDecimal.valueOf(7)));

        assertEquals(Boolean.TRUE, expression.compareValues(8.0, 8));
        assertEquals(Boolean.TRUE, expression.compareValues(8.0, 8L));
        assertEquals(Boolean.TRUE, expression.compareValues(8.0, 8.0f));
        assertEquals(Boolean.TRUE, expression.compareValues(8.0, BigInteger.valueOf(8)));
        assertEquals(Boolean.TRUE, expression.compareValues(8.0, BigDecimal.valueOf(8)));

        assertEquals(Boolean.TRUE, expression.compareValues(BigInteger.valueOf(9), (byte) 9));
        assertEquals(Boolean.TRUE, expression.compareValues(BigInteger.valueOf(9), (short) 9));
        assertEquals(Boolean.TRUE, expression.compareValues(BigInteger.valueOf(9), 9));
        assertEquals(Boolean.TRUE, expression.compareValues(BigInteger.valueOf(9), 9L));
        assertEquals(Boolean.TRUE, expression.compareValues(BigInteger.valueOf(9), 9.0f));
        assertEquals(Boolean.TRUE, expression.compareValues(BigInteger.valueOf(9), 9.0));
        assertEquals(Boolean.TRUE, expression.compareValues(BigInteger.valueOf(9), BigDecimal.valueOf(9)));

        assertEquals(Boolean.TRUE, expression.compareValues(BigDecimal.valueOf(10), (byte) 10));
        assertEquals(Boolean.TRUE, expression.compareValues(BigDecimal.valueOf(10), (short) 10));
        assertEquals(Boolean.TRUE, expression.compareValues(BigDecimal.valueOf(10), 10));
        assertEquals(Boolean.TRUE, expression.compareValues(BigDecimal.valueOf(10), 10L));
        assertEquals(Boolean.TRUE, expression.compareValues(BigDecimal.valueOf(10), 10.0f));
        assertEquals(Boolean.TRUE, expression.compareValues(BigDecimal.valueOf(10), 10.0));
        assertEquals(Boolean.TRUE, expression.compareValues(BigDecimal.valueOf(10), BigInteger.valueOf(10)));
    }

    @Test
    void shouldConvertRightStringOperandToSupportedTypesWhenEnabled() {
        ComparisonExpression.CONVERT_STRING_EXPRESSIONS.set(Boolean.TRUE);

        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.TRUE, expression.compareValues(Boolean.TRUE, "true"));
        assertEquals(Boolean.TRUE, expression.compareValues((byte) 1, "1"));
        assertEquals(Boolean.TRUE, expression.compareValues((short) 2, "2"));
        assertEquals(Boolean.TRUE, expression.compareValues(3, "3"));
        assertEquals(Boolean.TRUE, expression.compareValues(4L, "4"));
        assertEquals(Boolean.TRUE, expression.compareValues(5.5f, "5.5"));
        assertEquals(Boolean.TRUE, expression.compareValues(6.5, "6.5"));
    }

    @Test
    void shouldConvertLeftStringOperandToSupportedTypesWhenEnabled() {
        ComparisonExpression.CONVERT_STRING_EXPRESSIONS.set(Boolean.TRUE);

        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.TRUE, expression.compareValues("true", Boolean.TRUE));
        assertEquals(Boolean.TRUE, expression.compareValues("1", (byte) 1));
        assertEquals(Boolean.TRUE, expression.compareValues("2", (short) 2));
        assertEquals(Boolean.TRUE, expression.compareValues("3", 3));
        assertEquals(Boolean.TRUE, expression.compareValues("4", 4L));
        assertEquals(Boolean.TRUE, expression.compareValues("5.5", 5.5f));
        assertEquals(Boolean.TRUE, expression.compareValues("6.5", 6.5));
        assertEquals(Boolean.TRUE, expression.compareValues("7", BigInteger.valueOf(7)));
        assertEquals(Boolean.TRUE, expression.compareValues("8.5", BigDecimal.valueOf(8.5)));
    }

    @Test
    void shouldReturnFalseForUnsupportedPairsAndBadStringConversions() {
        ComparisonExpression.CONVERT_STRING_EXPRESSIONS.set(Boolean.TRUE);

        TestComparisonExpression expression = testExpressionForCompareOnly();

        assertEquals(Boolean.FALSE, expression.compareValues(Boolean.TRUE, 1));
        assertEquals(Boolean.FALSE, expression.compareValues("abc", 1));
        assertEquals(Boolean.FALSE, expression.compareValues(1, "abc"));
        assertEquals(Boolean.FALSE, expression.compareValues(BigInteger.valueOf(1), Boolean.TRUE));
        assertEquals(Boolean.FALSE, expression.compareValues(BigDecimal.valueOf(1), Boolean.TRUE));
        assertEquals(Boolean.FALSE, expression.compareValues("value", new ObjectComparable("value")));
    }

    private void assertBooleanResult(BooleanExpression expression, boolean expected) throws ExpressionException {
        Field result = expression.evaluate(expressionContext);
        assertEquals(expected, result.getValue());
    }

    private Expression expressionReturningValue(Object value) throws ExpressionException {
        Expression expression = mock(Expression.class);
        when(expression.evaluate(expressionContext)).thenReturn(wrapWithField(value));
        return expression;
    }

    private TestComparisonExpression testExpressionForCompareOnly() {
        return new TestComparisonExpression(dummyExpression(), dummyExpression());
    }

    private Expression dummyExpression() {
        return new Expression() {
            @Override
            public Field evaluate(ExpressionContext expressionContext) {
                throw new UnsupportedOperationException("evaluate should not be called in compare-only tests");
            }

            @Override
            public String toString() {
                return "dummy";
            }
        };
    }

    private static class TestComparisonExpression extends ComparisonExpression {

        TestComparisonExpression(Expression left, Expression right) {
            super(left, right);
        }

        Boolean compareValues(Comparable left, Comparable right) {
            return compare(left, right);
        }

        @Override
        protected boolean asBoolean(int answer) {
            return answer == 0;
        }

        @Override
        public String getExpressionSymbol() {
            return "==";
        }
    }

    private static class ObjectComparable implements Comparable<ObjectComparable> {

        private final String value;

        private ObjectComparable(String value) {
            this.value = value;
        }

        @Override
        public int compareTo(ObjectComparable other) {
            return value.compareTo(other.value);
        }
    }
}
