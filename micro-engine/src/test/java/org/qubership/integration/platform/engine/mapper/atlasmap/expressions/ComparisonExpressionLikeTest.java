package org.qubership.integration.platform.engine.mapper.atlasmap.expressions;

import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.internal.BooleanExpression;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MapperTestUtils;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ComparisonExpressionLikeTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldMatchLikeExpressionWithPercentWildcard() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLike(
                MapperTestUtils.expressionReturningValue(expressionContext, "alpha-beta-gamma"),
                "alpha%gamma",
                null
        );

        MapperTestUtils.assertBooleanResult(expressionContext, expression, true);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldMatchLikeExpressionWithUnderscoreWildcard() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLike(
                MapperTestUtils.expressionReturningValue(expressionContext, "abc"),
                "a_c",
                null
        );

        MapperTestUtils.assertBooleanResult(expressionContext, expression, true);
    }

    @Test
    void shouldTreatRegexControlCharactersAsLiteralsInLikeExpression() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLike(
                MapperTestUtils.expressionReturningValue(expressionContext, "a.b"),
                "a.b",
                null
        );

        MapperTestUtils.assertBooleanResult(expressionContext, expression, true);
    }

    @Test
    void shouldEscapePercentWhenEscapeCharacterProvided() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLike(
                MapperTestUtils.expressionReturningValue(expressionContext, "a%b"),
                "a\\%b",
                "\\"
        );

        MapperTestUtils.assertBooleanResult(expressionContext, expression, true);
    }

    @Test
    void shouldReturnFalseWhenLikeOperandIsNotString() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLike(
                MapperTestUtils.expressionReturningValue(expressionContext, 10),
                "%1%",
                null
        );

        MapperTestUtils.assertBooleanResult(expressionContext, expression, false);
        assertFalse(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnNullWhenLikeOperandEvaluatesToNull() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLike(
                MapperTestUtils.expressionReturningValue(expressionContext, null),
                "%value%",
                null
        );

        assertNull(expression.evaluate(expressionContext));
    }

    @Test
    void shouldThrowNullPointerExceptionWhenLikeMatchesEvaluatesNullCurrentBehavior() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLike(
                MapperTestUtils.expressionReturningValue(expressionContext, null),
                "%value%",
                null
        );

        assertThrows(NullPointerException.class, () -> expression.matches(expressionContext));
    }

    @Test
    void shouldReturnFalseWhenValueDoesNotMatchLikePattern() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLike(
                MapperTestUtils.expressionReturningValue(expressionContext, "alpha-beta"),
                "alpha%gamma",
                null
        );

        MapperTestUtils.assertBooleanResult(expressionContext, expression, false);
    }

    @Test
    void shouldReturnTrueWhenValueDoesNotMatchLikePatternAndNotLikeUsed() throws Exception {
        BooleanExpression expression = ComparisonExpression.createNotLike(
                MapperTestUtils.expressionReturningValue(expressionContext, "alpha-beta"),
                "alpha%gamma",
                null
        );

        MapperTestUtils.assertBooleanResult(expressionContext, expression, true);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldThrowExceptionWhenEscapeLiteralLengthIsInvalid() {
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> ComparisonExpression.createLike(MapperTestUtils.dummyExpression("dummy"), "%value%", "ab"));

        assertTrue(exception.getMessage().contains("ESCAPE string litteral is invalid"));
    }
}
