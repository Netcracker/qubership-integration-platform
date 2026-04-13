package org.qubership.integration.platform.engine.mapper.atlasmap.expressions;

import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.internal.BooleanExpression;
import io.atlasmap.expression.internal.VariableExpression;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MapperTestUtils;

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
                MapperTestUtils.expressionReturningValue(expressionContext, 5),
                MapperTestUtils.expressionReturningValue(expressionContext, 1),
                MapperTestUtils.expressionReturningValue(expressionContext, 10)
        );

        MapperTestUtils.assertBooleanResult(expressionContext, expression);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnTrueWhenValueIsOutsideRangeInNotBetweenExpression() throws Exception {
        BooleanExpression expression = ComparisonExpression.createNotBetween(
                MapperTestUtils.expressionReturningValue(expressionContext, 15),
                MapperTestUtils.expressionReturningValue(expressionContext, 1),
                MapperTestUtils.expressionReturningValue(expressionContext, 10)
        );

        MapperTestUtils.assertBooleanResult(expressionContext, expression);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnTrueWhenValuesAreEqualInGreaterThanEqualExpression() throws Exception {
        BooleanExpression expression = ComparisonExpression.createGreaterThanEqual(
                MapperTestUtils.expressionReturningValue(expressionContext, 10),
                MapperTestUtils.expressionReturningValue(expressionContext, 10)
        );

        MapperTestUtils.assertBooleanResult(expressionContext, expression);
    }

    @Test
    void shouldReturnTrueWhenLeftIsLessThanRightInLessThanExpression() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLessThan(
                MapperTestUtils.expressionReturningValue(expressionContext, 5),
                MapperTestUtils.expressionReturningValue(expressionContext, 10)
        );

        MapperTestUtils.assertBooleanResult(expressionContext, expression);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnTrueWhenVariableValueIsInElementsUsingCreateInFilter() throws Exception {
        VariableExpression variableExpression = new VariableExpression("name");
        when(expressionContext.getVariable("name")).thenReturn(wrapWithField("B"));

        BooleanExpression expression = ComparisonExpression.createInFilter(variableExpression, List.of("A", "B", "C"));

        MapperTestUtils.assertBooleanResult(expressionContext, expression);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldReturnTrueWhenVariableValueIsNotInElementsUsingCreateNotInFilter() throws Exception {
        VariableExpression variableExpression = new VariableExpression("name");
        when(expressionContext.getVariable("name")).thenReturn(wrapWithField("Z"));

        BooleanExpression expression = ComparisonExpression.createNotInFilter(variableExpression, List.of("A", "B", "C"));

        MapperTestUtils.assertBooleanResult(expressionContext, expression);
        assertTrue(expression.matches(expressionContext));
    }

    @Test
    void shouldThrowExceptionWhenCreateInFilterReceivesNonVariableExpression() {
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> ComparisonExpression.createInFilter(MapperTestUtils.dummyExpression("dummy"), List.of("A", "B")));

        assertTrue(exception.getMessage().contains("Expected a property for In expression"));
    }

    @Test
    void shouldThrowExceptionWhenCreateNotInFilterReceivesNonVariableExpression() {
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> ComparisonExpression.createNotInFilter(MapperTestUtils.dummyExpression("dummy"), List.of("A", "B")));

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
        assertDoesNotThrow(() -> ComparisonExpression.checkLessThanOperand(MapperTestUtils.dummyExpression("dummy")));
    }

    @Test
    void shouldReturnFalseWhenComparisonMatchesEvaluatesToNull() throws Exception {
        BooleanExpression expression = ComparisonExpression.createLessThan(
                MapperTestUtils.expressionReturningValue(expressionContext, null),
                MapperTestUtils.dummyExpression("dummy")
        );

        assertFalse(expression.matches(expressionContext));
    }
}
