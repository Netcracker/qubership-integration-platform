package org.qubership.integration.platform.engine.mapper.atlasmap.expressions;

import io.atlasmap.api.AtlasConstants;
import io.atlasmap.api.AtlasException;
import io.atlasmap.core.AtlasUtil;
import io.atlasmap.core.DefaultAtlasContext;
import io.atlasmap.core.DefaultAtlasFunctionResolver;
import io.atlasmap.core.DefaultAtlasSession;
import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.ExpressionException;
import io.atlasmap.spi.AtlasModule;
import io.atlasmap.v2.AuditStatus;
import io.atlasmap.v2.ConstantField;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldGroup;
import io.atlasmap.v2.PropertyField;
import io.atlasmap.v2.SimpleField;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CustomAtlasExpressionProcessorTest {

    @Test
    void shouldReturnImmediatelyWhenExpressionIsNull() {
        DefaultAtlasSession session = mock(DefaultAtlasSession.class);

        try (MockedStatic<CustomExpression> customExpression = mockStatic(CustomExpression.class)) {
            CustomAtlasExpressionProcessor.processExpression(session, null);

            customExpression.verifyNoInteractions();
            verifyNoInteractions(session);
        }
    }

    @Test
    void shouldReturnImmediatelyWhenExpressionIsBlank() {
        DefaultAtlasSession session = mock(DefaultAtlasSession.class);

        try (MockedStatic<CustomExpression> customExpression = mockStatic(CustomExpression.class)) {
            CustomAtlasExpressionProcessor.processExpression(session, "   ");

            customExpression.verifyNoInteractions();
            verifyNoInteractions(session);
        }
    }

    @Test
    void shouldSetSourceFieldWhenParsedExpressionReturnsField() throws Exception {
        SimpleField initialField = simpleField("doc1", "/source", "initial");
        SessionState sessionState = sessionState(initialField);

        SimpleField resultField = simpleField("doc2", "/result", "resolved");
        Expression parsedExpression = mock(Expression.class);
        when(parsedExpression.evaluate(any(ExpressionContext.class))).thenReturn(resultField);

        try (MockedStatic<CustomExpression> customExpression = mockStatic(CustomExpression.class)) {
            customExpression.when(() -> CustomExpression.parse("expr", DefaultAtlasFunctionResolver.getInstance())).thenReturn(parsedExpression);

            CustomAtlasExpressionProcessor.processExpression(sessionState.session(), "expr");
        }

        assertSame(resultField, sessionState.session().head().getSourceField());
    }

    @Test
    void shouldCreateSimpleFieldWhenParsedExpressionReturnsNull() throws Exception {
        SimpleField initialField = simpleField("doc1", "/source", "initial");
        SessionState sessionState = sessionState(initialField);

        Expression parsedExpression = mock(Expression.class);
        when(parsedExpression.evaluate(any(ExpressionContext.class))).thenReturn(null);

        try (MockedStatic<CustomExpression> customExpression = mockStatic(CustomExpression.class)) {
            customExpression.when(() -> CustomExpression.parse("expr", DefaultAtlasFunctionResolver.getInstance()))
                    .thenReturn(parsedExpression);

            CustomAtlasExpressionProcessor.processExpression(sessionState.session(), "expr");
        }

        Field result = sessionState.session().head().getSourceField();
        assertInstanceOf(SimpleField.class, result);
        assertNotSame(initialField, result);
        assertEquals("doc1", result.getDocId());
        assertEquals("/source", result.getPath());
        assertNull(result.getValue());
    }

    @Test
    void shouldResolveNormalFieldUsingModuleFromPathPrefix() throws Exception {
        SimpleField parent = simpleField("doc1", "/source", "initial");
        SessionState sessionState = sessionState(parent);

        AtlasModule sourceModule = mock(AtlasModule.class);
        Map<String, AtlasModule> sourceModules = new HashMap<>();
        sourceModules.put("doc1", sourceModule);
        when(sessionState.atlasContext().getSourceModules()).thenReturn(sourceModules);

        SimpleField resolvedField = simpleField("doc1", "/source", "resolved");
        doAnswer(invocation -> {
            sessionState.session().head().setSourceField(resolvedField);
            return null;
        }).when(sourceModule).readSourceValue(sessionState.session());

        Expression parsedExpression = expressionResolvingPath("doc1:/source");

        try (MockedStatic<CustomExpression> customExpression = mockStatic(CustomExpression.class)) {
            customExpression.when(() -> CustomExpression.parse("expr", DefaultAtlasFunctionResolver.getInstance())).thenReturn(parsedExpression);

            CustomAtlasExpressionProcessor.processExpression(sessionState.session(), "expr");
        }

        verify(sourceModule).readSourceValue(sessionState.session());
        assertSame(resolvedField, sessionState.session().head().getSourceField());
    }

    @Test
    void shouldResolveConstantFieldUsingConstantsModule() throws Exception {
        ConstantField parent = new ConstantField();
        parent.setDocId("constantsDoc");
        parent.setPath("/const");
        parent.setValue("initial");

        SessionState sessionState = sessionState(parent);

        AtlasModule sourceModule = mock(AtlasModule.class);
        Map<String, AtlasModule> sourceModules = new HashMap<>();
        sourceModules.put(AtlasConstants.CONSTANTS_DOCUMENT_ID, sourceModule);
        when(sessionState.atlasContext().getSourceModules()).thenReturn(sourceModules);

        ConstantField resolvedField = new ConstantField();
        resolvedField.setDocId("constantsDoc");
        resolvedField.setPath("/const");
        resolvedField.setValue("resolved");

        doAnswer(invocation -> {
            sessionState.session().head().setSourceField(resolvedField);
            return null;
        }).when(sourceModule).readSourceValue(sessionState.session());

        Expression parsedExpression = expressionResolvingPath("constantsDoc:/const");

        try (MockedStatic<CustomExpression> customExpression = mockStatic(CustomExpression.class)) {
            customExpression.when(() -> CustomExpression.parse("expr", DefaultAtlasFunctionResolver.getInstance())).thenReturn(parsedExpression);

            CustomAtlasExpressionProcessor.processExpression(sessionState.session(), "expr");
        }

        verify(sourceModule).readSourceValue(sessionState.session());
        assertSame(resolvedField, sessionState.session().head().getSourceField());
    }

    @Test
    void shouldResolvePropertyFieldUsingPropertiesModule() throws Exception {
        PropertyField parent = new PropertyField();
        parent.setDocId("propertiesDoc");
        parent.setPath("/property");
        parent.setValue("initial");

        SessionState sessionState = sessionState(parent);

        AtlasModule sourceModule = mock(AtlasModule.class);
        Map<String, AtlasModule> sourceModules = new HashMap<>();
        sourceModules.put(AtlasConstants.PROPERTIES_SOURCE_DOCUMENT_ID, sourceModule);
        when(sessionState.atlasContext().getSourceModules()).thenReturn(sourceModules);

        PropertyField resolvedField = new PropertyField();
        resolvedField.setDocId("propertiesDoc");
        resolvedField.setPath("/property");
        resolvedField.setValue("resolved");

        doAnswer(invocation -> {
            sessionState.session().head().setSourceField(resolvedField);
            return null;
        }).when(sourceModule).readSourceValue(sessionState.session());

        Expression parsedExpression = expressionResolvingPath("propertiesDoc:/property");

        try (MockedStatic<CustomExpression> customExpression = mockStatic(CustomExpression.class)) {
            customExpression.when(() -> CustomExpression.parse("expr", DefaultAtlasFunctionResolver.getInstance())).thenReturn(parsedExpression);

            CustomAtlasExpressionProcessor.processExpression(sessionState.session(), "expr");
        }

        verify(sourceModule).readSourceValue(sessionState.session());
        assertSame(resolvedField, sessionState.session().head().getSourceField());
    }

    @Test
    void shouldUseChildrenFromAnonymousFieldGroup() throws Exception {
        FieldGroup parentGroup = new FieldGroup();
        parentGroup.setPath("");

        SimpleField child = simpleField("doc1", "/child", "child");
        SimpleField ignoredChild = new SimpleField();

        parentGroup.getField().add(child);
        parentGroup.getField().add(ignoredChild);

        SessionState sessionState = sessionState(parentGroup);

        AtlasModule sourceModule = mock(AtlasModule.class);
        Map<String, AtlasModule> sourceModules = new HashMap<>();
        sourceModules.put("doc1", sourceModule);
        when(sessionState.atlasContext().getSourceModules()).thenReturn(sourceModules);

        SimpleField resolvedField = simpleField("doc1", "/child", "resolved");
        doAnswer(invocation -> {
            sessionState.session().head().setSourceField(resolvedField);
            return null;
        }).when(sourceModule).readSourceValue(sessionState.session());

        Expression parsedExpression = expressionResolvingPath("doc1:/child");

        try (MockedStatic<CustomExpression> customExpression = mockStatic(CustomExpression.class)) {
            customExpression.when(() -> CustomExpression.parse("expr", DefaultAtlasFunctionResolver.getInstance())).thenReturn(parsedExpression);

            CustomAtlasExpressionProcessor.processExpression(sessionState.session(), "expr");
        }

        verify(sourceModule).readSourceValue(sessionState.session());
        assertSame(resolvedField, sessionState.session().head().getSourceField());
    }

    @Test
    void shouldAddAuditWhenModuleForPathIsMissing() throws Exception {
        SimpleField parent = simpleField("doc1", "/source", "initial");
        SessionState sessionState = sessionState(parent);
        when(sessionState.atlasContext().getSourceModules()).thenReturn(new HashMap<>());

        Expression parsedExpression = expressionResolvingPath("doc1:/source");

        try (MockedStatic<CustomExpression> customExpression = mockStatic(CustomExpression.class); MockedStatic<AtlasUtil> atlasUtil = mockStatic(AtlasUtil.class, CALLS_REAL_METHODS)) {

            customExpression.when(() -> CustomExpression.parse("expr", DefaultAtlasFunctionResolver.getInstance())).thenReturn(parsedExpression);

            atlasUtil.when(() -> AtlasUtil.addAudit(eq(sessionState.session()), eq("expr"), anyString(), eq(AuditStatus.ERROR), isNull())).thenAnswer(invocation -> null);

            CustomAtlasExpressionProcessor.processExpression(sessionState.session(), "expr");

            atlasUtil.verify(() -> AtlasUtil.addAudit(eq(sessionState.session()), eq("expr"), argThat(message -> message.contains("Expression processing error [expr]") && message.contains("Module for the path 'doc1:/source' is not found")), eq(AuditStatus.ERROR), isNull()));
        }

        assertSame(parent, sessionState.session().head().getSourceField());
    }

    @Test
    void shouldAddAuditWhenSourceModuleReadFails() throws Exception {
        SimpleField parent = simpleField("doc1", "/source", "initial");
        SessionState sessionState = sessionState(parent);

        AtlasModule sourceModule = mock(AtlasModule.class);
        Map<String, AtlasModule> sourceModules = new HashMap<>();
        sourceModules.put("doc1", sourceModule);
        when(sessionState.atlasContext().getSourceModules()).thenReturn(sourceModules);

        doThrow(new RuntimeException("boom")).when(sourceModule).readSourceValue(sessionState.session());

        Expression parsedExpression = expressionResolvingPath("doc1:/source");

        try (MockedStatic<CustomExpression> customExpression = mockStatic(CustomExpression.class); MockedStatic<AtlasUtil> atlasUtil = mockStatic(AtlasUtil.class, CALLS_REAL_METHODS)) {

            customExpression.when(() -> CustomExpression.parse("expr", DefaultAtlasFunctionResolver.getInstance())).thenReturn(parsedExpression);

            atlasUtil.when(() -> AtlasUtil.addAudit(eq(sessionState.session()), eq("expr"), anyString(), eq(AuditStatus.ERROR), isNull())).thenAnswer(invocation -> null);

            CustomAtlasExpressionProcessor.processExpression(sessionState.session(), "expr");

            atlasUtil.verify(() -> AtlasUtil.addAudit(eq(sessionState.session()), eq("expr"), argThat(message -> message.contains("Expression processing error [expr]") && message.contains("boom")), eq(AuditStatus.ERROR), isNull()));
        }
    }

    @Test
    void shouldAddAuditWhenExpressionParsingFails() throws AtlasException {
        SessionState sessionState = sessionState(null);

        try (MockedStatic<CustomExpression> customExpression = mockStatic(CustomExpression.class); MockedStatic<AtlasUtil> atlasUtil = mockStatic(AtlasUtil.class, CALLS_REAL_METHODS)) {

            customExpression.when(() -> CustomExpression.parse("expr", DefaultAtlasFunctionResolver.getInstance())).thenThrow(new RuntimeException("boom"));

            atlasUtil.when(() -> AtlasUtil.addAudit(eq(sessionState.session()), eq("expr"), anyString(), eq(AuditStatus.ERROR), isNull())).thenAnswer(invocation -> null);

            CustomAtlasExpressionProcessor.processExpression(sessionState.session(), "expr");

            atlasUtil.verify(() -> AtlasUtil.addAudit(eq(sessionState.session()), eq("expr"), argThat(message -> message.contains("Expression processing error [expr]: boom")), eq(AuditStatus.ERROR), isNull()));
        }
    }

    private Expression expressionResolvingPath(String path) throws ExpressionException {
        Expression expression = mock(Expression.class);
        when(expression.evaluate(any(ExpressionContext.class))).thenAnswer(invocation -> {
            ExpressionContext context = invocation.getArgument(0);
            return context.getVariable(path);
        });
        return expression;
    }

    private SessionState sessionState(Field initialField) throws AtlasException {
        DefaultAtlasContext atlasContext = mock(DefaultAtlasContext.class);
        DefaultAtlasSession session = new DefaultAtlasSession(atlasContext);

        if (initialField != null) {
            session.head().setSourceField(initialField);
        }

        return new SessionState(session, atlasContext);
    }

    private SimpleField simpleField(String docId, String path, Object value) {
        SimpleField field = new SimpleField();
        field.setDocId(docId);
        field.setPath(path);
        field.setValue(value);
        return field;
    }

    private record SessionState(DefaultAtlasSession session, DefaultAtlasContext atlasContext) {
    }
}
