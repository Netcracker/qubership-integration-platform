package org.qubership.integration.platform.engine.mapper.atlasmap.expressions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldType;
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
class CustomPlusArithmeticExpressionTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldConcatenateStringsWhenLeftFieldTypeIsString() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field("foo", FieldType.STRING)),
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field("bar", FieldType.STRING))
        );

        Field result = expression.evaluate(expressionContext);

        assertEquals("foobar", result.getValue());
    }

    @Test
    void shouldConcatenateStringAndNumberWhenLeftFieldTypeIsString() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field("foo", FieldType.STRING)),
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field(12, FieldType.INTEGER))
        );

        Field result = expression.evaluate(expressionContext);

        assertEquals("foo12", result.getValue());
    }

    @Test
    void shouldConcatenateWhenLeftValueIsStringAndFieldTypeIsNull() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field("foo", null)),
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field("bar", null))
        );

        Field result = expression.evaluate(expressionContext);

        assertEquals("foobar", result.getValue());
    }

    @Test
    void shouldReplaceNullLeftStringValueWithEmptyString() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field(null, FieldType.STRING)),
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field("bar", FieldType.STRING))
        );

        Field result = expression.evaluate(expressionContext);

        assertEquals("bar", result.getValue());
    }

    @Test
    void shouldReplaceNullRightStringValueWithEmptyString() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field("foo", FieldType.STRING)),
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field(null, FieldType.STRING))
        );

        Field result = expression.evaluate(expressionContext);

        assertEquals("foo", result.getValue());
    }

    @Test
    void shouldReturnWrappedNullWhenLeftValueIsNullInDefaultBranch() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field(null, FieldType.INTEGER)),
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field(5, FieldType.INTEGER))
        );

        Field result = expression.evaluate(expressionContext);

        assertNotNull(result);
        assertNull(result.getValue());
    }

    @Test
    void shouldReturnNullWhenRightFieldIsNullInDefaultBranch() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field(10, FieldType.INTEGER)),
                MapperTestUtils.expressionReturning(expressionContext, null)
        );

        Field result = expression.evaluate(expressionContext);

        assertNull(result);
    }

    @Test
    void shouldReturnNullWhenRightValueIsNullInDefaultBranch() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field(10, FieldType.INTEGER)),
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field(null, FieldType.INTEGER))
        );

        Field result = expression.evaluate(expressionContext);

        assertNull(result);
    }

    @Test
    void shouldAddNumbersWhenBothOperandsAreNumeric() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field(10, FieldType.INTEGER)),
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field(5, FieldType.INTEGER))
        );

        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(Number.class, result.getValue());
        assertEquals(15, ((Number) result.getValue()).intValue());
    }

    @Test
    void shouldAddNumbersWhenLeftFieldTypeIsNullAndValueIsNumeric() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field(7, null)),
                MapperTestUtils.expressionReturning(expressionContext, MapperTestUtils.field(8, null))
        );

        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(Number.class, result.getValue());
        assertEquals(15, ((Number) result.getValue()).intValue());
    }

    @Test
    void shouldReturnPlusSymbol() {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(MapperTestUtils.dummyExpression("dummy"), MapperTestUtils.dummyExpression("dummy"));

        assertEquals("(dummy + dummy)", expression.toString());
    }
}
