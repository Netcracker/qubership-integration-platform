package org.qubership.integration.platform.engine.mapper.atlasmap.expressions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.ExpressionException;
import io.atlasmap.expression.internal.BooleanExpression;
import io.atlasmap.v2.Field;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static io.atlasmap.v2.AtlasModelFactory.wrapWithField;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ComparisonExpressionLikeTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldMatchLikeExpressionWithPercentWildcard() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLike(
                expressionReturningValue("alpha-beta-gamma"),
                "alpha%gamma",
                null
        );

        assertBooleanResult(expression, true);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldMatchLikeExpressionWithUnderscoreWildcard() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLike(
                expressionReturningValue("abc"),
                "a_c",
                null
        );

        assertBooleanResult(expression, true);
    }

    @Test
    void shouldTreatRegexControlCharactersAsLiteralsInLikeExpression() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLike(
                expressionReturningValue("a.b"),
                "a.b",
                null
        );

        assertBooleanResult(expression, true);
    }

    @Test
    void shouldEscapePercentWhenEscapeCharacterProvided() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLike(
                expressionReturningValue("a%b"),
                "a\\%b",
                "\\"
        );

        assertBooleanResult(expression, true);
    }

    @Test
    void shouldReturnFalseWhenLikeOperandIsNotString() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLike(
                expressionReturningValue(Integer.valueOf(10)),
                "%1%",
                null
        );

        assertBooleanResult(expression, false);
        assertFalse(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnNullWhenLikeOperandEvaluatesToNull() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLike(
                expressionReturningValue(null),
                "%value%",
                null
        );

        assertNull(expression.evaluate(expressionContext));
    }

    @Test
    void shouldThrowNullPointerExceptionWhenLikeMatchesEvaluatesNullCurrentBehavior() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLike(
                expressionReturningValue(null),
                "%value%",
                null
        );

        assertThrows(NullPointerException.class, () -> expression.matches(expressionContext));
    }

    @Test
    void shouldReturnFalseWhenValueDoesNotMatchLikePattern() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLike(
                expressionReturningValue("alpha-beta"),
                "alpha%gamma",
                null
        );

        assertBooleanResult(expression, false);
    }

    @Test
    void shouldReturnTrueWhenValueDoesNotMatchLikePatternAndNotLikeUsed() throws Exception {
        BooleanExpression expression = ComparisonExpression.createNotLike(
                expressionReturningValue("alpha-beta"),
                "alpha%gamma",
                null
        );

        assertBooleanResult(expression, true);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldThrowExceptionWhenEscapeLiteralLengthIsInvalid() {
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> ComparisonExpression.createLike(dummyExpression(), "%value%", "ab"));

        assertTrue(exception.getMessage().contains("ESCAPE string litteral is invalid"));
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

    private Expression dummyExpression() {
        return new Expression() {
            @Override
            public Field evaluate(ExpressionContext expressionContext) {
                throw new UnsupportedOperationException("evaluate should not be called");
            }

            @Override
            public String toString() {
                return "dummy";
            }
        };
    }
}
