package org.qubership.integration.platform.engine.mapper.atlasmap.expressions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.ExpressionException;
import io.atlasmap.expression.internal.BooleanExpression;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldStatus;
import io.atlasmap.v2.FieldType;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static io.atlasmap.v2.AtlasModelFactory.wrapWithField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ComparisonExpressionEqualityTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldReturnTrueWhenComplexFieldIsNotFoundAndComparedToNull() throws Exception {
        Expression left = expressionReturning(complexField(FieldStatus.NOT_FOUND));

        BooleanExpression expression = ComparisonExpression.createIsNull(left);

        assertBooleanResult(expression, true);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnFalseWhenComplexFieldExistsAndComparedToNull() throws Exception {
        Expression left = expressionReturning(complexField(null));

        BooleanExpression expression = ComparisonExpression.createIsNull(left);

        assertBooleanResult(expression, false);
    }

    @Test
    void shouldReturnFalseWhenOnlyOneSideIsNullInEqualExpression() throws Exception {
        Expression left = expressionReturning(field(null));
        Expression right = expressionReturning(field("value"));

        BooleanExpression expression = ComparisonExpression.createEqual(left, right);

        assertBooleanResult(expression, false);
    }

    @Test
    void shouldReturnTrueWhenValuesAreEqualInEqualExpression() throws Exception {
        Expression left = expressionReturning(field("same"));
        Expression right = expressionReturning(field("same"));

        BooleanExpression expression = ComparisonExpression.createEqual(left, right);

        assertBooleanResult(expression, true);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnFalseWhenValuesAreDifferentInEqualExpression() throws Exception {
        Expression left = expressionReturning(field("left"));
        Expression right = expressionReturning(field("right"));

        BooleanExpression expression = ComparisonExpression.createEqual(left, right);

        assertBooleanResult(expression, false);
    }

    @Test
    void shouldReturnTrueWhenComparableValuesAreEqualByComparison() throws Exception {
        Expression left = expressionReturning(field(10));
        Expression right = expressionReturning(field(10L));

        BooleanExpression expression = ComparisonExpression.createEqual(left, right);

        assertBooleanResult(expression, true);
    }

    @Test
    void shouldReturnTrueWhenValuesAreDifferentInNotEqualExpression() throws Exception {
        Expression left = expressionReturning(field("left"));
        Expression right = expressionReturning(field("right"));

        BooleanExpression expression = ComparisonExpression.createNotEqual(left, right);

        assertBooleanResult(expression, true);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnFalseWhenValuesAreEqualInNotEqualExpression() throws Exception {
        Expression left = expressionReturning(field("same"));
        Expression right = expressionReturning(field("same"));

        BooleanExpression expression = ComparisonExpression.createNotEqual(left, right);

        assertBooleanResult(expression, false);
    }

    @Test
    void shouldReturnTrueWhenValueIsNullInIsNullExpression() throws Exception {
        Expression left = expressionReturning(field(null));

        BooleanExpression expression = ComparisonExpression.createIsNull(left);

        assertBooleanResult(expression, true);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnTrueWhenValueIsNotNullInIsNotNullExpression() throws Exception {
        Expression left = expressionReturning(field("value"));

        BooleanExpression expression = ComparisonExpression.createIsNotNull(left);

        assertBooleanResult(expression, true);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnFalseWhenValueIsNullInIsNotNullExpression() throws Exception {
        Expression left = expressionReturning(field(null));

        BooleanExpression expression = ComparisonExpression.createIsNotNull(left);

        assertBooleanResult(expression, false);
    }

    private void assertBooleanResult(BooleanExpression expression, boolean expected) throws ExpressionException {
        Field result = expression.evaluate(expressionContext);

        assertEquals(expected, result.getValue());
    }

    private Expression expressionReturning(Field field) throws ExpressionException {
        Expression expression = mock(Expression.class);
        when(expression.evaluate(expressionContext)).thenReturn(field);
        return expression;
    }

    private Field field(Object value) {
        return wrapWithField(value);
    }

    private Field complexField(FieldStatus status) {
        Field field = wrapWithField("complex");
        field.setFieldType(FieldType.COMPLEX);
        field.setStatus(status);
        return field;
    }
}
