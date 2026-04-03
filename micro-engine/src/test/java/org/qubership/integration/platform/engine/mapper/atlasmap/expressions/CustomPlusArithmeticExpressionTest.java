package org.qubership.integration.platform.engine.mapper.atlasmap.expressions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.ExpressionException;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldType;
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
class CustomPlusArithmeticExpressionTest {

    @Mock
    ExpressionContext expressionContext;

    @Test
    void shouldConcatenateStringsWhenLeftFieldTypeIsString() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                expressionReturning(field("foo", FieldType.STRING)),
                expressionReturning(field("bar", FieldType.STRING))
        );

        Field result = expression.evaluate(expressionContext);

        assertEquals("foobar", result.getValue());
    }

    @Test
    void shouldConcatenateStringAndNumberWhenLeftFieldTypeIsString() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                expressionReturning(field("foo", FieldType.STRING)),
                expressionReturning(field(12, FieldType.INTEGER))
        );

        Field result = expression.evaluate(expressionContext);

        assertEquals("foo12", result.getValue());
    }

    @Test
    void shouldConcatenateWhenLeftValueIsStringAndFieldTypeIsNull() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                expressionReturning(field("foo", null)),
                expressionReturning(field("bar", null))
        );

        Field result = expression.evaluate(expressionContext);

        assertEquals("foobar", result.getValue());
    }

    @Test
    void shouldReplaceNullLeftStringValueWithEmptyString() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                expressionReturning(field(null, FieldType.STRING)),
                expressionReturning(field("bar", FieldType.STRING))
        );

        Field result = expression.evaluate(expressionContext);

        assertEquals("bar", result.getValue());
    }

    @Test
    void shouldReplaceNullRightStringValueWithEmptyString() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                expressionReturning(field("foo", FieldType.STRING)),
                expressionReturning(field(null, FieldType.STRING))
        );

        Field result = expression.evaluate(expressionContext);

        assertEquals("foo", result.getValue());
    }

    @Test
    void shouldReturnWrappedNullWhenLeftValueIsNullInDefaultBranch() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                expressionReturning(field(null, FieldType.INTEGER)),
                expressionReturning(field(5, FieldType.INTEGER))
        );

        Field result = expression.evaluate(expressionContext);

        assertNotNull(result);
        assertNull(result.getValue());
    }

    @Test
    void shouldReturnNullWhenRightFieldIsNullInDefaultBranch() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                expressionReturning(field(10, FieldType.INTEGER)),
                expressionReturning(null)
        );

        Field result = expression.evaluate(expressionContext);

        assertNull(result);
    }

    @Test
    void shouldReturnNullWhenRightValueIsNullInDefaultBranch() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                expressionReturning(field(10, FieldType.INTEGER)),
                expressionReturning(field(null, FieldType.INTEGER))
        );

        Field result = expression.evaluate(expressionContext);

        assertNull(result);
    }

    @Test
    void shouldAddNumbersWhenBothOperandsAreNumeric() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                expressionReturning(field(10, FieldType.INTEGER)),
                expressionReturning(field(5, FieldType.INTEGER))
        );

        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(Number.class, result.getValue());
        assertEquals(15, ((Number) result.getValue()).intValue());
    }

    @Test
    void shouldAddNumbersWhenLeftFieldTypeIsNullAndValueIsNumeric() throws Exception {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(
                expressionReturning(field(7, null)),
                expressionReturning(field(8, null))
        );

        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(Number.class, result.getValue());
        assertEquals(15, ((Number) result.getValue()).intValue());
    }

    @Test
    void shouldReturnPlusSymbol() {
        Expression expression = CustomPlusArithmeticExpression.createCustomPlus(dummyExpression(), dummyExpression());

        assertEquals("(dummy + dummy)", expression.toString());
    }

    private Expression expressionReturning(Field field) throws ExpressionException {
        Expression expression = mock(Expression.class);
        when(expression.evaluate(expressionContext)).thenReturn(field);
        return expression;
    }

    private Field field(Object value, FieldType fieldType) {
        Field field = wrapWithField(value);
        field.setFieldType(fieldType);
        return field;
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
