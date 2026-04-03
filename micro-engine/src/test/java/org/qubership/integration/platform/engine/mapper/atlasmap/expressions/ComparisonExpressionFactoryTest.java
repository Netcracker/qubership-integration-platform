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

import java.util.List;

import static io.atlasmap.v2.AtlasModelFactory.wrapWithField;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ComparisonExpressionFactoryTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldReturnTrueWhenValueIsWithinRangeInBetweenExpression() throws Exception {
        BooleanExpression expression = ComparisonExpression.createBetween(
                expressionReturningValue(5),
                expressionReturningValue(1),
                expressionReturningValue(10)
        );

        assertBooleanResult(expression);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnTrueWhenValueIsOutsideRangeInNotBetweenExpression() throws Exception {
        BooleanExpression expression = ComparisonExpression.createNotBetween(
                expressionReturningValue(15),
                expressionReturningValue(1),
                expressionReturningValue(10)
        );

        assertBooleanResult(expression);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnTrueWhenValuesAreEqualInGreaterThanEqualExpression() throws Exception {
        BooleanExpression expression = ComparisonExpression.createGreaterThanEqual(
                expressionReturningValue(10),
                expressionReturningValue(10)
        );

        assertBooleanResult(expression);
    }

    @Test
    void shouldReturnTrueWhenLeftIsLessThanRightInLessThanExpression() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLessThan(
                expressionReturningValue(5),
                expressionReturningValue(10)
        );

        assertBooleanResult(expression);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnTrueWhenVariableValueIsInElementsUsingCreateInFilter() throws Exception {
        VariableExpression variableExpression = new VariableExpression("name");
        when(expressionContext.getVariable("name")).thenReturn(wrapWithField("B"));

        BooleanExpression expression = ComparisonExpression.createInFilter(variableExpression, List.of("A", "B", "C"));

        assertBooleanResult(expression);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnTrueWhenVariableValueIsNotInElementsUsingCreateNotInFilter() throws Exception {
        VariableExpression variableExpression = new VariableExpression("name");
        when(expressionContext.getVariable("name")).thenReturn(wrapWithField("Z"));

        BooleanExpression expression = ComparisonExpression.createNotInFilter(variableExpression, List.of("A", "B", "C"));

        assertBooleanResult(expression);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldThrowExceptionWhenCreateInFilterReceivesNonVariableExpression() {
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> ComparisonExpression.createInFilter(dummyExpression(), List.of("A", "B")));

        assertTrue(exception.getMessage().contains("Expected a property for In expression"));
    }

    @Test
    void shouldThrowExceptionWhenCreateNotInFilterReceivesNonVariableExpression() {
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> ComparisonExpression.createNotInFilter(dummyExpression(), List.of("A", "B")));

        assertTrue(exception.getMessage().contains("Expected a property for In expression"));
    }

    @Test
    void shouldThrowExceptionWhenCheckLessThanOperandReceivesBooleanExpression() {
        BooleanExpression booleanExpression = mock(BooleanExpression.class);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> ComparisonExpression.checkLessThanOperand(booleanExpression));

        assertTrue(exception.getMessage().contains("cannot be compared"));
    }

    @Test
    void shouldNotThrowWhenCheckLessThanOperandReceivesRegularExpression() {
        assertDoesNotThrow(() -> ComparisonExpression.checkLessThanOperand(dummyExpression()));
    }

    @Test
    void shouldReturnFalseWhenComparisonMatchesEvaluatesToNull() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLessThan(
                expressionReturningValue(null),
                dummyExpression()
        );

        assertFalse(expression.matches(expressionContext));
    }

    private void assertBooleanResult(BooleanExpression expression) throws ExpressionException {
        Field result = expression.evaluate(expressionContext);

        assertNotNull(result);
        assertEquals(true, result.getValue());
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
