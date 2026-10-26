package org.qubership.integration.platform.engine.mapper.atlasmap.expressions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.internal.BooleanExpression;
import io.atlasmap.v2.FieldStatus;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MapperTestUtils;

import static io.atlasmap.v2.AtlasModelFactory.wrapWithField;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ComparisonExpressionEqualityTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldReturnTrueWhenComplexFieldIsNotFoundAndComparedToNull() throws Exception {
        Expression left = MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.complexField(FieldStatus.NOT_FOUND));

        BooleanExpression expression = ComparisonExpression.createIsNull(left);

        MapperTestUtils.assertBooleanResult(expressionContext, expression, true);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnFalseWhenComplexFieldExistsAndComparedToNull() throws Exception {
        Expression left = MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.complexField(null));

        BooleanExpression expression = ComparisonExpression.createIsNull(left);

        MapperTestUtils.assertBooleanResult(expressionContext, expression, false);
    }

    @Test
    void shouldReturnFalseWhenOnlyOneSideIsNullInEqualExpression() throws Exception {
        Expression left = MapperTestUtils.expressionReturning(expressionContext, wrapWithField(null));
        Expression right = MapperTestUtils.expressionReturning(expressionContext, wrapWithField("value"));

        BooleanExpression expression = ComparisonExpression.createEqual(left, right);

        MapperTestUtils.assertBooleanResult(expressionContext, expression, false);
    }

    @Test
    void shouldReturnTrueWhenValuesAreEqualInEqualExpression() throws Exception {
        Expression left = MapperTestUtils.expressionReturning(expressionContext, wrapWithField("same"));
        Expression right = MapperTestUtils.expressionReturning(expressionContext, wrapWithField("same"));

        BooleanExpression expression = ComparisonExpression.createEqual(left, right);

        MapperTestUtils.assertBooleanResult(expressionContext, expression, true);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnFalseWhenValuesAreDifferentInEqualExpression() throws Exception {
        Expression left = MapperTestUtils.expressionReturning(expressionContext, wrapWithField("left"));
        Expression right = MapperTestUtils.expressionReturning(expressionContext, wrapWithField("right"));

        BooleanExpression expression = ComparisonExpression.createEqual(left, right);

        MapperTestUtils.assertBooleanResult(expressionContext, expression, false);
    }

    @Test
    void shouldReturnTrueWhenComparableValuesAreEqualByComparison() throws Exception {
        Expression left = MapperTestUtils.expressionReturning(expressionContext, wrapWithField(10));
        Expression right = MapperTestUtils.expressionReturning(expressionContext, wrapWithField(10L));

        BooleanExpression expression = ComparisonExpression.createEqual(left, right);

        MapperTestUtils.assertBooleanResult(expressionContext, expression, true);
    }

    @Test
    void shouldReturnTrueWhenValuesAreDifferentInNotEqualExpression() throws Exception {
        Expression left = MapperTestUtils.expressionReturning(expressionContext, wrapWithField("left"));
        Expression right = MapperTestUtils.expressionReturning(expressionContext, wrapWithField("right"));

        BooleanExpression expression = ComparisonExpression.createNotEqual(left, right);

        MapperTestUtils.assertBooleanResult(expressionContext, expression, true);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnFalseWhenValuesAreEqualInNotEqualExpression() throws Exception {
        Expression left = MapperTestUtils.expressionReturning(expressionContext, wrapWithField("same"));
        Expression right = MapperTestUtils.expressionReturning(expressionContext, wrapWithField("same"));

        BooleanExpression expression = ComparisonExpression.createNotEqual(left, right);

        MapperTestUtils.assertBooleanResult(expressionContext, expression, false);
    }

    @Test
    void shouldReturnTrueWhenValueIsNullInIsNullExpression() throws Exception {
        Expression left = MapperTestUtils.expressionReturning(expressionContext, wrapWithField(null));

        BooleanExpression expression = ComparisonExpression.createIsNull(left);

        MapperTestUtils.assertBooleanResult(expressionContext, expression, true);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnTrueWhenValueIsNotNullInIsNotNullExpression() throws Exception {
        Expression left = MapperTestUtils.expressionReturning(expressionContext, wrapWithField("value"));

        BooleanExpression expression = ComparisonExpression.createIsNotNull(left);

        MapperTestUtils.assertBooleanResult(expressionContext, expression, true);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnFalseWhenValueIsNullInIsNotNullExpression() throws Exception {
        Expression left = MapperTestUtils.expressionReturning(expressionContext, wrapWithField(null));

        BooleanExpression expression = ComparisonExpression.createIsNotNull(left);

        MapperTestUtils.assertBooleanResult(expressionContext, expression, false);
    }
}
