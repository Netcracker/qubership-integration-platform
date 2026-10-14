package org.qubership.integration.platform.engine.mapper.atlasmap.expressions;

import io.atlasmap.expression.internal.ConstantExpression;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComparisonExpressionTest {

    static Stream<Arguments> mixedNumberTypes() {
        return Stream.of(
                Arguments.of((byte) 2, 2f),
                Arguments.of((byte) 2, 2d),
                Arguments.of((short) 2, 2f),
                Arguments.of((short) 2, 2d),
                Arguments.of(2, 2f),
                Arguments.of(2, 2d),
                Arguments.of(2L, 2f),
                Arguments.of(2L, 2d),
                Arguments.of(2f, 2),
                Arguments.of(2f, 2L),
                Arguments.of(2.5f, 2.5d),
                Arguments.of(2d, 2),
                Arguments.of(2d, 2L),
                Arguments.of(2.5d, 2.5f)
        );
    }

    @ParameterizedTest
    @MethodSource("mixedNumberTypes")
    void equalNumbersOfDifferentTypesCompareEqual(Number leftValue, Number rightValue) throws Exception {
        ConstantExpression left = new ConstantExpression(leftValue);
        ConstantExpression right = new ConstantExpression(rightValue);

        assertEquals(Boolean.TRUE, ComparisonExpression.createLessThanEqual(left, right).evaluate(null).getValue());
        assertEquals(Boolean.TRUE, ComparisonExpression.createGreaterThanEqual(left, right).evaluate(null).getValue());
    }

    @ParameterizedTest
    @CsvSource({"a.c, true", "abc, false"})
    void likeTreatsRegexpControlCharacterAsLiteral(String value, boolean matches) throws Exception {
        ConstantExpression expression = new ConstantExpression(value);

        assertEquals(matches, ComparisonExpression.createLike(expression, "a.c", null).evaluate(null).getValue());
    }
}
