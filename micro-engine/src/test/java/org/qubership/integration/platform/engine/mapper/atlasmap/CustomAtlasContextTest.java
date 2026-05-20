package org.qubership.integration.platform.engine.mapper.atlasmap;

import io.atlasmap.api.AtlasException;
import io.atlasmap.api.AtlasSession;
import io.atlasmap.core.AtlasUtil;
import io.atlasmap.core.DefaultAtlasContextFactory;
import io.atlasmap.core.DefaultAtlasSession;
import io.atlasmap.json.v2.JsonField;
import io.atlasmap.spi.AtlasModule;
import io.atlasmap.spi.FieldDirection;
import io.atlasmap.v2.AtlasMapping;
import io.atlasmap.v2.AuditStatus;
import io.atlasmap.v2.CopyTo;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldGroup;
import io.atlasmap.v2.Mapping;
import io.atlasmap.v2.Validation;
import io.atlasmap.v2.Validations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.mapper.atlasmap.expressions.CustomAtlasExpressionProcessor;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MapperTestUtils;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CustomAtlasContextTest {

    @Mock
    private DefaultAtlasContextFactory factory;
    @Mock
    private AtlasModule module;
    @Mock
    private AtlasSession atlasSession;

    private TestableCustomAtlasContext context;

    @BeforeEach
    void setUp() {
        context = new TestableCustomAtlasContext(factory);
    }

    @Test
    void shouldInstantiateWithUriConstructor() {
        CustomAtlasContext atlasContext = new CustomAtlasContext(URI.create("atlasmapping://test"));

        assertNotNull(atlasContext);
    }

    @Test
    void shouldInstantiateWithMappingConstructor() {
        CustomAtlasContext atlasContext = new CustomAtlasContext(factory, new AtlasMapping());

        assertNotNull(atlasContext);
    }

    @Test
    void shouldGetAndSetCachedValidationResult() {
        ValidationResult validationResult = ValidationResult.builder().build();

        context.setCachedValidationResult(validationResult);

        assertSame(validationResult, context.getCachedValidationResult());
    }

    @Test
    void shouldRestoreCachedValidationsWhenValidationResultExists() throws Exception {
        Validations validations = new Validations();
        Validation validation = new Validation();
        ValidationResult validationResult = ValidationResult.builder()
                .validations(List.of(validation))
                .build();

        when(atlasSession.getValidations()).thenReturn(validations);
        context.setCachedValidationResult(validationResult);

        context.processValidation(atlasSession);

        assertEquals(1, validations.getValidation().size());
        assertSame(validation, validations.getValidation().get(0));
    }

    @Test
    void shouldThrowCachedAtlasExceptionWhenRestoringValidationResult() {
        Validations validations = new Validations();
        AtlasException atlasException = new AtlasException("boom");
        ValidationResult validationResult = ValidationResult.builder()
                .validations(List.of(new Validation()))
                .exception(atlasException)
                .build();

        when(atlasSession.getValidations()).thenReturn(validations);
        context.setCachedValidationResult(validationResult);

        AtlasException exception = assertThrows(AtlasException.class, () -> context.processValidation(atlasSession));

        assertSame(atlasException, exception);
        assertEquals(1, validations.getValidation().size());
    }

    @Test
    void shouldThrowCachedRuntimeExceptionWhenRestoringValidationResult() {
        Validations validations = new Validations();
        RuntimeException runtimeException = new RuntimeException("boom");
        ValidationResult validationResult = ValidationResult.builder()
                .validations(List.of(new Validation()))
                .runtimeException(runtimeException)
                .build();

        when(atlasSession.getValidations()).thenReturn(validations);
        context.setCachedValidationResult(validationResult);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> context.processValidation(atlasSession));

        assertSame(runtimeException, exception);
        assertEquals(1, validations.getValidation().size());
    }

    @Test
    void shouldProcessExpressionWhenInputFieldGroupAndExpressionPresent() throws Exception {
        Mapping mapping = mock(Mapping.class);
        FieldGroup sourceGroup = MapperTestUtils.fieldGroup(MapperTestUtils.field("/source/a"));
        DefaultAtlasSession session = sessionWithMapping(mapping);

        when(mapping.getInputFieldGroup()).thenReturn(sourceGroup);
        when(mapping.getExpression()).thenReturn("expr");

        try (MockedStatic<CustomAtlasExpressionProcessor> expressionProcessor = mockStatic(CustomAtlasExpressionProcessor.class)) {
            context.invokeProcessSourceFieldMapping(session);

            verify(session.head()).setSourceField(sourceGroup);
            expressionProcessor.verify(() -> CustomAtlasExpressionProcessor.processExpression(session, "expr"));
        }
    }

    @Test
    void shouldProcessExpressionWhenInputFieldsAndExpressionPresent() {
        Mapping mapping = mock(Mapping.class);
        JsonField sourceField1 = MapperTestUtils.field("/source/a");
        JsonField sourceField2 = MapperTestUtils.field("/source/b");
        DefaultAtlasSession session = sessionWithMapping(mapping);

        when(mapping.getInputFieldGroup()).thenReturn(null);
        when(mapping.getInputField()).thenReturn(new ArrayList<>(List.of(sourceField1, sourceField2)));
        when(mapping.getExpression()).thenReturn("expr");

        try (MockedStatic<CustomAtlasExpressionProcessor> expressionProcessor = mockStatic(CustomAtlasExpressionProcessor.class)) {
            context.invokeProcessSourceFieldMapping(session);

            ArgumentCaptor<Field> captor = ArgumentCaptor.forClass(Field.class);
            verify(session.head()).setSourceField(captor.capture());

            Field captured = captor.getValue();
            assertInstanceOf(FieldGroup.class, captured);

            FieldGroup group = (FieldGroup) captured;
            assertEquals(2, group.getField().size());
            assertSame(sourceField1, group.getField().get(0));
            assertSame(sourceField2, group.getField().get(1));

            expressionProcessor.verify(() -> CustomAtlasExpressionProcessor.processExpression(session, "expr"));
        }
    }

    @Test
    void shouldAddWarnAuditWhenMappingDoesNotContainSourceFieldsOrExpression() {
        Mapping mapping = mock(Mapping.class);
        DefaultAtlasSession session = sessionWithMapping(mapping);

        when(mapping.getInputFieldGroup()).thenReturn(null);
        when(mapping.getInputField()).thenReturn(new ArrayList<>());
        when(mapping.getAlias()).thenReturn("alias1");
        when(mapping.getDescription()).thenReturn("desc1");

        context.invokeProcessSourceFieldMapping(session);

        verify(session.head()).addAudit(
                eq(AuditStatus.WARN),
                eq(null),
                contains("Mapping does not contain expression or at least one source field")
        );
    }

    @Test
    void shouldAddErrorAuditWhenUnexpectedExceptionThrownAndSourceFieldIsNull() {
        Mapping mapping = mock(Mapping.class);
        DefaultAtlasSession session = sessionWithMapping(mapping);

        when(mapping.getInputFieldGroup()).thenThrow(new RuntimeException("boom"));
        when(session.head().getSourceField()).thenReturn(null);

        context.invokeProcessSourceFieldMapping(session);

        verify(session.head()).addAudit(
                eq(AuditStatus.ERROR),
                eq(null),
                contains("Unexpected exception is thrown while reading source field: boom")
        );
    }

    @Test
    void shouldAddErrorAuditWhenExpressionProcessingThrowsException() {
        Mapping mapping = mock(Mapping.class);
        FieldGroup sourceGroup = MapperTestUtils.fieldGroup(MapperTestUtils.field("/source/a"));
        DefaultAtlasSession session = sessionWithMapping(mapping);

        when(mapping.getInputFieldGroup()).thenReturn(sourceGroup);
        when(mapping.getExpression()).thenReturn("expr");
        when(session.head().getSourceField()).thenReturn(sourceGroup);

        try (MockedStatic<CustomAtlasExpressionProcessor> expressionProcessor = mockStatic(CustomAtlasExpressionProcessor.class)) {
            expressionProcessor.when(() -> CustomAtlasExpressionProcessor.processExpression(session, "expr"))
                    .thenThrow(new RuntimeException("boom"));

            context.invokeProcessSourceFieldMapping(session);

            verify(session.head()).addAudit(
                    eq(AuditStatus.ERROR),
                    eq(sourceGroup),
                    contains("Unexpected exception is thrown while reading source field: boom")
            );
        }
    }

    @Test
    void shouldApplyCopyToSingleIndexAndRemoveActionWhenProcessingSourceFields() throws Exception {
        Mapping mapping = mock(Mapping.class);
        DefaultAtlasSession session = sessionWithMapping(mapping);

        JsonField sourceField = MapperTestUtils.field("/source/a");
        CopyTo copyTo = new CopyTo();
        copyTo.setIndex("2");
        sourceField.getActions().add(copyTo);

        JsonField outputField = MapperTestUtils.field("/target/orders<>/id");
        List<Field> inputFields = new ArrayList<>(List.of(sourceField));
        JsonField processedField = MapperTestUtils.field("/processed/a");

        when(mapping.getInputFieldGroup()).thenReturn(null);
        when(mapping.getInputField()).thenReturn(inputFields);
        when(mapping.getOutputField()).thenReturn(new ArrayList<>(List.of(outputField)));
        when(mapping.getExpression()).thenReturn(null);

        when(module.isSupportedField(sourceField)).thenReturn(true);
        when(session.head().getSourceField()).thenReturn(sourceField);

        context.resolvedModule = module;
        context.applyFieldResults.add(processedField);

        context.invokeProcessSourceFieldMapping(session);

        verify(module).readSourceValue(session);
        assertSame(processedField, inputFields.getFirst());
        assertTrue(sourceField.getActions().isEmpty());
    }

    @Test
    void shouldProcessCopyToRecursivelyInsideFieldGroup() throws Exception {
        Mapping mapping = mock(Mapping.class);
        DefaultAtlasSession session = sessionWithMapping(mapping);

        JsonField nestedField = MapperTestUtils.field("/source/a");
        CopyTo copyTo = new CopyTo();
        copyTo.setIndex("4");
        nestedField.getActions().add(copyTo);

        FieldGroup group = MapperTestUtils.fieldGroup(nestedField);
        JsonField outputField = MapperTestUtils.field("/target/orders<>/id");
        JsonField processedNestedField = MapperTestUtils.field("/processed/a");
        FieldGroup processedGroup = MapperTestUtils.fieldGroup(processedNestedField);

        when(mapping.getInputFieldGroup()).thenReturn(null);
        when(mapping.getInputField()).thenReturn(new ArrayList<>(List.of(group)));
        when(mapping.getOutputField()).thenReturn(new ArrayList<>(List.of(outputField)));
        when(mapping.getExpression()).thenReturn(null);

        when(module.isSupportedField(nestedField)).thenReturn(true);
        when(session.head().getSourceField()).thenReturn(nestedField, group);

        context.resolvedModule = module;
        context.applyFieldResults.add(processedNestedField);
        context.applyFieldResults.add(processedGroup);

        context.invokeProcessSourceFieldMapping(session);

        verify(module).readSourceValue(session);
        assertTrue(nestedField.getActions().isEmpty());
        assertSame(processedNestedField, group.getField().get(0));
        assertSame(nestedField, context.applyFieldArguments.get(0));
        assertSame(group, context.applyFieldArguments.get(1));
    }

    @Test
    void shouldReturnEarlyFromCopyToProcessingWhenFirstFieldHasNoCopyToCurrentBehavior() throws Exception {
        Mapping mapping = mock(Mapping.class);
        DefaultAtlasSession session = sessionWithMapping(mapping);

        JsonField firstField = MapperTestUtils.field("/source/a");
        JsonField secondField = MapperTestUtils.field("/source/b");
        CopyTo copyTo = new CopyTo();
        copyTo.setIndex("3");
        secondField.getActions().add(copyTo);

        JsonField processedFirst = MapperTestUtils.field("/processed/a");
        JsonField processedSecond = MapperTestUtils.field("/processed/b");
        List<Field> inputFields = new ArrayList<>(List.of(firstField, secondField));

        when(mapping.getInputFieldGroup()).thenReturn(null);
        when(mapping.getInputField()).thenReturn(inputFields);
        when(mapping.getExpression()).thenReturn(null);

        when(module.isSupportedField(firstField)).thenReturn(true);
        when(module.isSupportedField(secondField)).thenReturn(true);
        when(session.head().getSourceField()).thenReturn(firstField, secondField);

        context.resolvedModule = module;
        context.applyFieldResults.add(processedFirst);
        context.applyFieldResults.add(processedSecond);

        context.invokeProcessSourceFieldMapping(session);

        verify(module, org.mockito.Mockito.times(2)).readSourceValue(session);
        assertEquals(1, secondField.getActions().size());
        assertSame(copyTo, secondField.getActions().getFirst());
        assertSame(processedFirst, inputFields.get(0));
        assertSame(processedSecond, inputFields.get(1));
    }

    @Test
    void shouldSkipCopyToProcessingWhenActionsAreNull() throws Exception {
        Mapping mapping = mock(Mapping.class);
        DefaultAtlasSession session = sessionWithMapping(mapping);

        JsonField sourceField = MapperTestUtils.fieldWithNullActions("/source/a");
        JsonField processedField = MapperTestUtils.field("/processed/a");
        List<Field> inputFields = new ArrayList<>(List.of(sourceField));

        when(mapping.getInputFieldGroup()).thenReturn(null);
        when(mapping.getInputField()).thenReturn(inputFields);
        when(mapping.getExpression()).thenReturn(null);

        when(module.isSupportedField(sourceField)).thenReturn(true);
        when(session.head().getSourceField()).thenReturn(sourceField);

        context.resolvedModule = module;
        context.applyFieldResults.add(processedField);

        context.invokeProcessSourceFieldMapping(session);

        verify(module).readSourceValue(session);
        assertSame(sourceField, context.applyFieldArguments.getFirst());
        assertSame(processedField, inputFields.getFirst());
    }

    @Test
    void shouldAddErrorAuditWhenCopyToContainsNegativeIndex() {
        Mapping mapping = mock(Mapping.class);
        DefaultAtlasSession session = sessionWithMapping(mapping);

        JsonField sourceField = MapperTestUtils.field("/source/a");
        CopyTo copyTo = new CopyTo();
        copyTo.setIndex("-1");
        sourceField.getActions().add(copyTo);

        when(mapping.getInputFieldGroup()).thenReturn(null);
        when(mapping.getInputField()).thenReturn(new ArrayList<>(List.of(sourceField)));
        when(mapping.getExpression()).thenReturn(null);
        when(session.head().getSourceField()).thenReturn(sourceField);

        context.invokeProcessSourceFieldMapping(session);

        verify(session.head()).addAudit(
                eq(AuditStatus.ERROR),
                eq(sourceField),
                contains("Unexpected exception is thrown while reading source field: Indexes must be >= 0")
        );
    }

    @Test
    void shouldAddAuditWhenModuleNotFoundForSourceField() {
        Mapping mapping = mock(Mapping.class);
        DefaultAtlasSession session = sessionWithMapping(mapping);
        JsonField sourceField = MapperTestUtils.field("/source/a");

        when(mapping.getInputFieldGroup()).thenReturn(null);
        when(mapping.getInputField()).thenReturn(new ArrayList<>(List.of(sourceField)));
        when(mapping.getExpression()).thenReturn(null);

        try (MockedStatic<AtlasUtil> atlasUtil = mockStatic(AtlasUtil.class)) {
            context.resolvedModule = null;

            context.invokeProcessSourceFieldMapping(session);

            atlasUtil.verify(() -> AtlasUtil.addAudit(
                    eq(session),
                    eq(sourceField),
                    contains("Module not found for docId"),
                    eq(AuditStatus.ERROR),
                    eq(null)
            ));
        }
    }

    @Test
    void shouldAddAuditWhenSourceFieldTypeIsUnsupported() {
        Mapping mapping = mock(Mapping.class);
        DefaultAtlasSession session = sessionWithMapping(mapping);
        JsonField sourceField = MapperTestUtils.field("/source/a");

        when(mapping.getInputFieldGroup()).thenReturn(null);
        when(mapping.getInputField()).thenReturn(new ArrayList<>(List.of(sourceField)));
        when(mapping.getExpression()).thenReturn(null);

        when(module.isSupportedField(sourceField)).thenReturn(false);
        when(module.getUri()).thenReturn("atlas:test");

        try (MockedStatic<AtlasUtil> atlasUtil = mockStatic(AtlasUtil.class)) {
            context.resolvedModule = module;

            context.invokeProcessSourceFieldMapping(session);

            atlasUtil.verify(() -> AtlasUtil.addAudit(
                    eq(session),
                    eq(sourceField),
                    contains("Unsupported source field type"),
                    eq(AuditStatus.ERROR),
                    eq(null)
            ));
        }
    }

    @Test
    void shouldProcessSourceFieldGroupRecursivelyAndApplyActionsToChildAndGroup() throws Exception {
        Mapping mapping = mock(Mapping.class);
        DefaultAtlasSession session = sessionWithMapping(mapping);

        JsonField childField = MapperTestUtils.field("/source/a");
        FieldGroup sourceGroup = MapperTestUtils.fieldGroup(childField);
        JsonField processedChild = MapperTestUtils.field("/processed/a");
        FieldGroup processedGroup = MapperTestUtils.fieldGroup(processedChild);

        when(mapping.getInputFieldGroup()).thenReturn(sourceGroup);
        when(mapping.getExpression()).thenReturn(null);

        when(module.isSupportedField(childField)).thenReturn(true);
        when(session.head().getSourceField()).thenReturn(childField, sourceGroup);

        context.resolvedModule = module;
        context.applyFieldResults.add(processedChild);
        context.applyFieldResults.add(processedGroup);

        context.invokeProcessSourceFieldMapping(session);

        verify(module).readSourceValue(session);
        assertSame(childField, context.applyFieldArguments.get(0));
        assertSame(sourceGroup, context.applyFieldArguments.get(1));
        assertSame(processedChild, sourceGroup.getField().getFirst());
        verify(session.head()).setSourceField(processedGroup);
    }

    @Test
    void shouldApplyGroupActionsEvenWhenNestedFieldGroupChildModuleIsMissing() {
        Mapping mapping = mock(Mapping.class);
        DefaultAtlasSession session = sessionWithMapping(mapping);

        JsonField childField = MapperTestUtils.field("/source/a");
        FieldGroup sourceGroup = MapperTestUtils.fieldGroup(childField);
        FieldGroup processedGroup = MapperTestUtils.fieldGroup(childField);

        when(mapping.getInputFieldGroup()).thenReturn(sourceGroup);
        when(mapping.getExpression()).thenReturn(null);

        context.resolvedModule = null;
        context.applyFieldResults.add(processedGroup);

        try (MockedStatic<AtlasUtil> atlasUtil = mockStatic(AtlasUtil.class)) {
            context.invokeProcessSourceFieldMapping(session);

            atlasUtil.verify(() -> AtlasUtil.addAudit(
                    eq(session),
                    eq(childField),
                    contains("Module not found for docId"),
                    eq(AuditStatus.ERROR),
                    eq(null)
            ));

            assertEquals(1, context.applyFieldArguments.size());
            verify(session.head()).setSourceField(sourceGroup);
            verify(session.head()).setSourceField(processedGroup);
        }
    }

    private static DefaultAtlasSession sessionWithMapping(Mapping mapping) {
        DefaultAtlasSession session = mock(DefaultAtlasSession.class, RETURNS_DEEP_STUBS);
        when(session.head().getMapping()).thenReturn(mapping);
        return session;
    }

    private static class TestableCustomAtlasContext extends CustomAtlasContext {
        private AtlasModule resolvedModule;
        private final List<Field> applyFieldArguments = new ArrayList<>();
        private final Deque<Field> applyFieldResults = new ArrayDeque<>();

        TestableCustomAtlasContext(DefaultAtlasContextFactory factory) {
            super(factory, URI.create("atlasmapping://test"));
        }

        void invokeProcessSourceFieldMapping(DefaultAtlasSession session) {
            super.processSourceFieldMapping(session);
        }

        @Override
        protected AtlasModule resolveModule(FieldDirection direction, Field field) {
            return resolvedModule;
        }

        @Override
        protected Field applyFieldActions(DefaultAtlasSession session, Field field) {
            applyFieldArguments.add(field);
            return applyFieldResults.isEmpty() ? field : applyFieldResults.removeFirst();
        }
    }
}
