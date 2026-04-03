package org.qubership.integration.platform.engine.mapper.atlasmap.expressions;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.FunctionResolver;
import io.atlasmap.expression.internal.VariableExpression;
import io.atlasmap.expression.parser.ParseException;
import io.atlasmap.expression.parser.ParserTokenManager;
import io.atlasmap.expression.parser.SimpleCharStream;
import io.atlasmap.v2.Field;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.atlasmap.v2.AtlasModelFactory.wrapWithField;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CustomExpressionParserTest {

    @Mock
    ExpressionContext expressionContext;
    @Mock
    FunctionResolver functionResolver;

    @Test
    void shouldParseStringVariableAdditionUsingCustomPlusExpression() throws Exception {
        CustomExpressionParser parser = parser("${left} + ${right}");

        when(expressionContext.getVariable("left")).thenReturn(wrapWithField("foo"));
        when(expressionContext.getVariable("right")).thenReturn(wrapWithField(12));

        Expression expression = parser.parse();
        Field result = expression.evaluate(expressionContext);

        assertEquals("foo12", result.getValue());
    }

    @Test
    void shouldParseNumericAddition() throws Exception {
        CustomExpressionParser parser = parser("10 + 5");

        Expression expression = parser.parse();
        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(Number.class, result.getValue());
        assertEquals(15, ((Number) result.getValue()).intValue());
    }

    @Test
    void shouldRespectParenthesesPrecedence() throws Exception {
        CustomExpressionParser parser = parser("(10 + 5) * 2");

        Expression expression = parser.parse();
        Field result = expression.evaluate(expressionContext);

        assertInstanceOf(Number.class, result.getValue());
        assertEquals(30, ((Number) result.getValue()).intValue());
    }

    @Test
    void shouldParseLogicalPrecedence() throws Exception {
        CustomExpressionParser parser = parser("false || true && false");

        Expression expression = parser.parse();
        Field result = expression.evaluate(expressionContext);

        assertEquals(Boolean.FALSE, result.getValue());
    }

    @Test
    void shouldParseVariableExpressionWhenVariableMethodCalled() throws Exception {
        CustomExpressionParser parser = parser("${customerId}");

        VariableExpression variableExpression = parser.variable();

        assertEquals("customerId", variableExpression.getName());
    }

    @Test
    void shouldResolveFunctionExpressionUsingFunctionResolver() throws Exception {
        CustomExpressionParser parser = parser("myFunc(1, ${name})");
        parser.functionResolver = functionResolver;

        Expression resolvedExpression = dummyExpression();
        when(functionResolver.resolve(eq("myFunc"), anyList())).thenReturn(resolvedExpression);

        Expression parsedExpression = parser.parse();

        assertSame(resolvedExpression, parsedExpression);
        verify(functionResolver).resolve(eq("myFunc"), argThat(args -> args.size() == 2));
    }

    @Test
    void shouldParseExpressionAfterReInitWithReader() throws Exception {
        CustomExpressionParser parser = parser("1 + 1");
        Expression first = parser.parse();

        parser.ReInit(new StringReader("2 + 3"));
        Expression second = parser.parse();

        assertEquals(2, ((Number) first.evaluate(expressionContext).getValue()).intValue());
        assertEquals(5, ((Number) second.evaluate(expressionContext).getValue()).intValue());
    }

    @Test
    void shouldParseExpressionAfterReInitWithInputStream() throws Exception {
        CustomExpressionParser parser =
                new CustomExpressionParser(new ByteArrayInputStream("1 + 2".getBytes(StandardCharsets.UTF_8)));

        Expression first = parser.parse();

        parser.ReInit(new ByteArrayInputStream("4 + 5".getBytes(StandardCharsets.UTF_8)));
        Expression second = parser.parse();

        assertEquals(3, ((Number) first.evaluate(expressionContext).getValue()).intValue());
        assertEquals(9, ((Number) second.evaluate(expressionContext).getValue()).intValue());
    }

    @Test
    void shouldReturnNextToken() {
        CustomExpressionParser parser = parser("1 + 2");

        assertNotNull(parser.getNextToken());
        assertNotNull(parser.getNextToken());
    }

    @Test
    void shouldReturnSpecificToken() {
        CustomExpressionParser parser = parser("1 + 2");

        assertNotNull(parser.getToken(0));
        assertNotNull(parser.getToken(1));
        assertNotNull(parser.getToken(2));
    }

    @Test
    void shouldParseAllSupportedLiteralTypes() throws Exception {
        assertEquals(Boolean.TRUE, parser("true").literal().evaluate(expressionContext).getValue());
        assertEquals(Boolean.FALSE, parser("false").literal().evaluate(expressionContext).getValue());
        assertNull(parser("null").literal().evaluate(expressionContext).getValue());

        assertEquals(42, ((Number) parser("42").literal().evaluate(expressionContext).getValue()).intValue());
        assertEquals(0, ((Number) parser("0").literal().evaluate(expressionContext).getValue()).intValue());
        assertEquals(16, ((Number) parser("0x10").literal().evaluate(expressionContext).getValue()).intValue());
        assertEquals(8, ((Number) parser("010").literal().evaluate(expressionContext).getValue()).intValue());
        assertEquals(12.5d, ((Number) parser("12.5").literal().evaluate(expressionContext).getValue()).doubleValue());

        assertEquals("abc", parser("'abc'").literal().evaluate(expressionContext).getValue());
    }

    @Test
    void shouldParseGreaterLessAndLessOrEqualExpressions() throws Exception {
        Expression greaterThan = parser("3 > 2").parse();
        Expression lessThan = parser("2 < 3").parse();
        Expression lessThanOrEqual = parser("2 <= 2").parse();

        assertEquals(Boolean.TRUE, greaterThan.evaluate(expressionContext).getValue());
        assertEquals(Boolean.TRUE, lessThan.evaluate(expressionContext).getValue());
        assertEquals(Boolean.TRUE, lessThanOrEqual.evaluate(expressionContext).getValue());
    }

    @Test
    void shouldParseMinusDivideAndModuloExpressions() throws Exception {
        Expression minus = parser("10 - 3").parse();
        Expression divide = parser("8 / 2").parse();
        Expression modulo = parser("7 % 4").parse();

        assertEquals(7, ((Number) minus.evaluate(expressionContext).getValue()).intValue());
        assertEquals(4, ((Number) divide.evaluate(expressionContext).getValue()).intValue());
        assertEquals(3, ((Number) modulo.evaluate(expressionContext).getValue()).intValue());
    }

    @Test
    void shouldResolveFunctionWithoutArguments() throws Exception {
        CustomExpressionParser parser = parser("myFunc()");
        parser.functionResolver = functionResolver;

        Expression resolvedExpression = dummyExpression();
        when(functionResolver.resolve(eq("myFunc"), argThat(List::isEmpty))).thenReturn(resolvedExpression);

        Expression parsedExpression = parser.parse();

        assertSame(resolvedExpression, parsedExpression);
        verify(functionResolver).resolve(eq("myFunc"), argThat(List::isEmpty));
    }

    @Test
    void shouldConstructAndReinitWithParserTokenManager() throws Exception {
        ParserTokenManager firstManager = tokenManager("1 + 2");
        CustomExpressionParser parser = new CustomExpressionParser(firstManager);

        Expression first = parser.parse();

        ParserTokenManager secondManager = tokenManager("4 + 5");
        parser.ReInit(secondManager);

        Expression second = parser.parse();

        assertEquals(3, ((Number) first.evaluate(expressionContext).getValue()).intValue());
        assertEquals(9, ((Number) second.evaluate(expressionContext).getValue()).intValue());
    }

    @Test
    void shouldGenerateParseExceptionDirectly() {
        CustomExpressionParser parser = parser("1 + )");
        parser.getToken(1);

        ParseException exception = parser.generateParseException();

        assertTrue(exception.getMessage().contains("Parse error at line"));
        assertTrue(exception.getMessage().contains("Encountered"));
    }

    @Test
    void shouldCallTracingMethods() {
        CustomExpressionParser parser = parser("1 + 1");

        assertDoesNotThrow(parser::enable_tracing);
        assertDoesNotThrow(parser::disable_tracing);
    }

    @Test
    void shouldParsePrimaryExprWhenLiteralProvided() throws Exception {
        CustomExpressionParser parser = parser("42");

        Expression expression = parser.primaryExpr();
        Field result = expression.evaluate(expressionContext);

        assertEquals(42, ((Number) result.getValue()).intValue());
    }

    @Test
    void shouldParsePrimaryExprWhenVariableProvided() throws Exception {
        CustomExpressionParser parser = parser("${customerId}");
        when(expressionContext.getVariable("customerId")).thenReturn(wrapWithField("C-1"));

        Expression expression = parser.primaryExpr();
        Field result = expression.evaluate(expressionContext);

        assertEquals("C-1", result.getValue());
        assertInstanceOf(VariableExpression.class, expression);
    }

    @Test
    void shouldParsePrimaryExprWhenParenthesizedExpressionProvided() throws Exception {
        CustomExpressionParser parser = parser("(1 + 2)");

        Expression expression = parser.primaryExpr();
        Field result = expression.evaluate(expressionContext);

        assertEquals(3, ((Number) result.getValue()).intValue());
    }

    @Test
    void shouldParsePrimaryExprWhenFunctionProvided() throws Exception {
        CustomExpressionParser parser = parser("myFunc()");
        parser.functionResolver = functionResolver;

        Expression resolvedExpression = dummyExpression();
        when(functionResolver.resolve(eq("myFunc"), argThat(List::isEmpty))).thenReturn(resolvedExpression);

        Expression expression = parser.primaryExpr();

        assertSame(resolvedExpression, expression);
        verify(functionResolver).resolve(eq("myFunc"), argThat(List::isEmpty));
    }

    @Test
    void shouldParseUnaryExprWhenUnaryPlusProvided() throws Exception {
        CustomExpressionParser parser = parser("+5");

        Expression expression = parser.unaryExpr();
        Field result = expression.evaluate(expressionContext);

        assertEquals(5, ((Number) result.getValue()).intValue());
    }

    @Test
    void shouldParseUnaryExprWhenUnaryMinusProvided() throws Exception {
        CustomExpressionParser parser = parser("-5");

        Expression expression = parser.unaryExpr();
        Field result = expression.evaluate(expressionContext);

        assertEquals(-5, ((Number) result.getValue()).intValue());
    }

    @Test
    void shouldParseUnaryExprWhenLogicalNotProvided() throws Exception {
        CustomExpressionParser parser = parser("!false");

        Expression expression = parser.unaryExpr();
        Field result = expression.evaluate(expressionContext);

        assertEquals(Boolean.TRUE, result.getValue());
    }

    @Test
    void shouldParseMultExprWhenChainedOperatorsProvided() throws Exception {
        CustomExpressionParser parser = parser("20 / 2 % 3 * 4");

        Expression expression = parser.multExpr();
        Field result = expression.evaluate(expressionContext);

        assertEquals(4, ((Number) result.getValue()).intValue());
    }

    @Test
    void shouldParseAddExpressionWhenChainedPlusAndMinusProvided() throws Exception {
        CustomExpressionParser parser = parser("10 + 5 - 3");

        Expression expression = parser.addExpression();
        Field result = expression.evaluate(expressionContext);

        assertEquals(12, ((Number) result.getValue()).intValue());
    }

    @Test
    void shouldParseComparisonExpressionWhenEqualBranchProvided() throws Exception {
        CustomExpressionParser parser = parser("1 == 1");

        Expression expression = parser.comparisonExpression();
        Field result = expression.evaluate(expressionContext);

        assertEquals(Boolean.TRUE, result.getValue());
    }

    @Test
    void shouldParseComparisonExpressionWhenGreaterThanBranchProvided() throws Exception {
        CustomExpressionParser parser = parser("3 > 2");

        Expression expression = parser.comparisonExpression();
        Field result = expression.evaluate(expressionContext);

        assertEquals(Boolean.TRUE, result.getValue());
    }

    @Test
    void shouldParseComparisonExpressionWhenGreaterThanEqualBranchProvided() throws Exception {
        CustomExpressionParser parser = parser("3 >= 3");

        Expression expression = parser.comparisonExpression();
        Field result = expression.evaluate(expressionContext);

        assertEquals(Boolean.TRUE, result.getValue());
    }

    @Test
    void shouldParseComparisonExpressionWhenLessThanBranchProvided() throws Exception {
        CustomExpressionParser parser = parser("2 < 3");

        Expression expression = parser.comparisonExpression();
        Field result = expression.evaluate(expressionContext);

        assertEquals(Boolean.TRUE, result.getValue());
    }

    @Test
    void shouldParseComparisonExpressionWhenLessThanEqualBranchProvided() throws Exception {
        CustomExpressionParser parser = parser("2 <= 2");

        Expression expression = parser.comparisonExpression();
        Field result = expression.evaluate(expressionContext);

        assertEquals(Boolean.TRUE, result.getValue());
    }

    @Test
    void shouldParseEqualityExpressionWhenNotEqualBranchProvided() throws Exception {
        CustomExpressionParser parser = parser("1 != 2");

        Expression expression = parser.equalityExpression();
        Field result = expression.evaluate(expressionContext);

        assertEquals(Boolean.TRUE, result.getValue());
    }

    @Test
    void shouldParseEqualityExpressionWhenMultipleEqualityOperatorsProvided() throws Exception {
        CustomExpressionParser parser = parser("1 == 1 != false");

        Expression expression = parser.equalityExpression();
        Field result = expression.evaluate(expressionContext);

        assertEquals(Boolean.TRUE, result.getValue());
    }

    @Test
    void shouldParseAndExpressionWhenMultipleAndOperatorsProvided() throws Exception {
        CustomExpressionParser parser = parser("true && true && false");

        Expression expression = parser.andExpression();
        Field result = expression.evaluate(expressionContext);

        assertEquals(Boolean.FALSE, result.getValue());
    }

    @Test
    void shouldParseOrExpressionWhenMultipleOrOperatorsProvided() throws Exception {
        CustomExpressionParser parser = parser("false || false || true");

        Expression expression = parser.orExpression();
        Field result = expression.evaluate(expressionContext);

        assertEquals(Boolean.TRUE, result.getValue());
    }

    @Test
    void shouldParseFunctionExprWhenNestedArgumentsProvided() throws Exception {
        CustomExpressionParser parser = parser("myFunc(1, 2 + 3, ${name})");
        parser.functionResolver = functionResolver;

        Expression resolvedExpression = dummyExpression();
        when(functionResolver.resolve(eq("myFunc"), argThat(args -> args.size() == 3))).thenReturn(resolvedExpression);

        Expression expression = parser.functionExpr();

        assertSame(resolvedExpression, expression);
        verify(functionResolver).resolve(eq("myFunc"), argThat(args -> args.size() == 3));
    }

    @Test
    void shouldMatchFunctionLookaheadMethods() throws Exception {
        assertFalse(invokeLookahead(parser("myFunc()"), 0, "jj_3R_22"));
        assertFalse(invokeLookahead(parser("myFunc()"), 0, "jj_3R_26"));
        assertFalse(invokeLookahead(parser("myFunc(1, 2)"), 2, "jj_3R_38"));
        assertFalse(invokeLookahead(parser("myFunc(1, 2)"), 3, "jj_3R_42"));
    }

    @Test
    void shouldMatchParenthesizedLookaheadMethods() throws Exception {
        assertFalse(invokeLookahead(parser("(1)"), 0, "jj_3R_18"));
        assertFalse(invokeLookahead(parser("(1)"), 0, "jj_3R_21"));
    }

    @Test
    void shouldMatchLogicalLookaheadMethods() throws Exception {
        assertFalse(invokeLookahead(parser("false || true"), 0, "jj_3R_25"));
        assertFalse(invokeLookahead(parser("false || true"), 1, "jj_3R_37"));

        assertFalse(invokeLookahead(parser("true && false"), 0, "jj_3R_36"));
        assertFalse(invokeLookahead(parser("true && false"), 1, "jj_3R_41"));
    }

    @Test
    void shouldMatchComparisonLookaheadMethods() throws Exception {
        assertFalse(invokeLookahead(parser("1 == 1"), 0, "jj_3R_40"));
        assertFalse(invokeLookahead(parser("1 == 1"), 0, "jj_3R_43"));
        assertFalse(invokeLookahead(parser("1 == 1"), 1, "jj_3R_44"));
        assertFalse(invokeLookahead(parser("1 == 1"), 1, "jj_3R_47"));
        assertFalse(invokeLookahead(parser("1 != 2"), 1, "jj_3R_48"));

        assertFalse(invokeLookahead(parser("1 > 0"), 1, "jj_3R_46"));
        assertFalse(invokeLookahead(parser("1 > 0"), 1, "jj_3R_50"));
        assertFalse(invokeLookahead(parser("1 >= 1"), 1, "jj_3R_51"));
        assertFalse(invokeLookahead(parser("1 < 2"), 1, "jj_3R_52"));
        assertFalse(invokeLookahead(parser("1 <= 2"), 1, "jj_3R_53"));
        assertFalse(invokeLookahead(parser("1 == 1"), 1, "jj_3R_54"));
    }

    @Test
    void shouldMatchArithmeticLookaheadMethods() throws Exception {
        assertFalse(invokeLookahead(parser("1 + 2"), 0, "jj_3R_45"));
        assertFalse(invokeLookahead(parser("1 + 2"), 1, "jj_3R_49"));
        assertFalse(invokeLookahead(parser("1 + 2"), 1, "jj_3R_55"));
        assertFalse(invokeLookahead(parser("1 - 2"), 1, "jj_3R_56"));
    }

    private CustomExpressionParser parser(String expression) {
        return new CustomExpressionParser(new StringReader(expression));
    }

    private ParserTokenManager tokenManager(String expression) {
        return new ParserTokenManager(new SimpleCharStream(new StringReader(expression), 1, 1));
    }

    private Expression dummyExpression() {
        return new Expression() {
            @Override
            public Field evaluate(ExpressionContext expressionContext) {
                return wrapWithField("resolved");
            }

            @Override
            public String toString() {
                return "resolved";
            }
        };
    }

    private boolean invokeLookahead(CustomExpressionParser parser, int tokensToAdvance, String methodName) throws Exception {
        advanceTokens(parser, tokensToAdvance);
        prepareLookaheadState(parser);

        Method method = CustomExpressionParser.class.getDeclaredMethod(methodName);
        method.setAccessible(true);

        return (Boolean) method.invoke(parser);
    }

    private void advanceTokens(CustomExpressionParser parser, int tokensToAdvance) {
        for (int i = 0; i < tokensToAdvance; i++) {
            parser.getNextToken();
        }
    }

    private void prepareLookaheadState(CustomExpressionParser parser) throws Exception {
        java.lang.reflect.Field tokenField = CustomExpressionParser.class.getDeclaredField("token");
        tokenField.setAccessible(true);
        Object token = tokenField.get(parser);

        java.lang.reflect.Field scanPosField = CustomExpressionParser.class.getDeclaredField("jj_scanpos");
        scanPosField.setAccessible(true);
        scanPosField.set(parser, token);

        java.lang.reflect.Field lastPosField = CustomExpressionParser.class.getDeclaredField("jj_lastpos");
        lastPosField.setAccessible(true);
        lastPosField.set(parser, token);

        java.lang.reflect.Field laField = CustomExpressionParser.class.getDeclaredField("jj_la");
        laField.setAccessible(true);
        laField.setInt(parser, Integer.MAX_VALUE);
    }
}
