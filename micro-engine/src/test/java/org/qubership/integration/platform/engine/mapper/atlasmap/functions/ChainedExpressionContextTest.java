package org.qubership.integration.platform.engine.mapper.atlasmap.functions;

import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.ExpressionException;
import io.atlasmap.v2.Field;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ChainedExpressionContextTest {

    @Mock
    ExpressionContext firstContent;
    @Mock
    ExpressionContext secondContent;
    @Mock
    Field firstField;
    @Mock
    Field secondField;

    @Test
    void shouldReturnFieldFromFirstContextWhenPresent() throws Exception {
        ChainedExpressionContext context = new ChainedExpressionContext(firstContent, secondContent);

        when(firstContent.getVariable("var")).thenReturn(firstField);

        Field result = context.getVariable("var");

        assertSame(firstField, result);
        verify(firstContent).getVariable("var");
        verifyNoInteractions(secondContent);
    }

    @Test
    void shouldReturnFieldFromSecondContextWhenFirstReturnsNull() throws Exception {
        ChainedExpressionContext context = new ChainedExpressionContext(firstContent, secondContent);

        when(firstContent.getVariable("var")).thenReturn(null);
        when(secondContent.getVariable("var")).thenReturn(secondField);

        Field result = context.getVariable("var");

        assertSame(secondField, result);
        verify(firstContent).getVariable("var");
        verify(secondContent).getVariable("var");
    }

    @Test
    void shouldPropagateExceptionFromFirstContext() throws Exception {
        ChainedExpressionContext context = new ChainedExpressionContext(firstContent, secondContent);
        ExpressionException exception = new ExpressionException("boom");

        when(firstContent.getVariable("var")).thenThrow(exception);

        ExpressionException result = assertThrows(ExpressionException.class, () -> context.getVariable("var"));

        assertSame(exception, result);
        verify(firstContent).getVariable("var");
        verifyNoInteractions(secondContent);
    }

    @Test
    void shouldPropagateExceptionFromSecondContextWhenFirstReturnsNull() throws Exception {
        ChainedExpressionContext context = new ChainedExpressionContext(firstContent, secondContent);
        ExpressionException exception = new ExpressionException("boom");

        when(firstContent.getVariable("var")).thenReturn(null);
        when(secondContent.getVariable("var")).thenThrow(exception);

        ExpressionException result = assertThrows(ExpressionException.class, () -> context.getVariable("var"));

        assertSame(exception, result);
        verify(firstContent).getVariable("var");
        verify(secondContent).getVariable("var");
    }
}
