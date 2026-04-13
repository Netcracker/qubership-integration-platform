package org.qubership.integration.platform.engine.mapper.atlasmap;

import io.atlasmap.api.AtlasException;
import io.atlasmap.api.AtlasSession;
import io.atlasmap.spi.AtlasInternalSession;
import io.atlasmap.v2.ConstantField;
import io.atlasmap.v2.Field;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.mapper.GeneratedField;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class QipGeneratedValueAtlasModuleTest {

    @Test
    void shouldSupportGeneratedField() {
        QipGeneratedValueAtlasModule module = new QipGeneratedValueAtlasModule();

        org.junit.jupiter.api.Assertions.assertTrue(module.isSupportedField(new GeneratedField()));
    }

    @Test
    void shouldNotSupportNonGeneratedField() {
        QipGeneratedValueAtlasModule module = new QipGeneratedValueAtlasModule();

        org.junit.jupiter.api.Assertions.assertFalse(module.isSupportedField(new ConstantField()));
    }

    @Test
    void shouldDoNothingInPreValidation() {
        QipGeneratedValueAtlasModule module = new QipGeneratedValueAtlasModule();
        AtlasInternalSession session = mock(AtlasInternalSession.class);

        assertDoesNotThrow(() -> module.processPreValidation(session));
    }

    @Test
    void shouldStoreGeneratedValueInSourcePropertiesWhenProcessingPreSourceExecution() throws Exception {
        TestableQipGeneratedValueAtlasModule module =
                new TestableQipGeneratedValueAtlasModule("atlas:generated:test", "doc1");
        ValueGeneratorFactory valueGeneratorFactory = mock(ValueGeneratorFactory.class);
        AtlasInternalSession session = mock(AtlasInternalSession.class);
        Map<String, Object> sourceProperties = new HashMap<>();
        ValueGeneratorInfo valueGeneratorInfo = valueGeneratorInfo("generatorName", List.of("p1", "p2"));
        Function<AtlasSession, String> generator = s -> "generated-value";

        setValueGeneratorFactory(module, valueGeneratorFactory);

        when(session.getSourceProperties()).thenReturn(sourceProperties);
        when(valueGeneratorFactory.getValueGenerator("generatorName", valueGeneratorInfo.parameters()))
                .thenReturn(generator);

        try (MockedStatic<ValueGeneratorInfoDecoder> decoder = mockStatic(ValueGeneratorInfoDecoder.class)) {
            decoder.when(() -> ValueGeneratorInfoDecoder.decode("atlas:generated:test"))
                    .thenReturn(valueGeneratorInfo);

            module.processPreSourceExecution(session);
        }

        assertEquals("generated-value", sourceProperties.get("Atlas.GeneratedValue.doc1"));
    }

    @Test
    void shouldWrapExceptionWhenProcessingPreSourceExecutionFails() throws Exception {
        TestableQipGeneratedValueAtlasModule module =
                new TestableQipGeneratedValueAtlasModule("atlas:generated:test", "doc1");
        ValueGeneratorFactory valueGeneratorFactory = mock(ValueGeneratorFactory.class);
        AtlasInternalSession session = mock(AtlasInternalSession.class);
        ValueGeneratorInfo valueGeneratorInfo = valueGeneratorInfo("generatorName", List.of("p1"));

        setValueGeneratorFactory(module, valueGeneratorFactory);

        try (MockedStatic<ValueGeneratorInfoDecoder> decoder = mockStatic(ValueGeneratorInfoDecoder.class)) {
            decoder.when(() -> ValueGeneratorInfoDecoder.decode("atlas:generated:test"))
                    .thenReturn(valueGeneratorInfo);
            when(valueGeneratorFactory.getValueGenerator("generatorName", valueGeneratorInfo.parameters()))
                    .thenThrow(new IllegalArgumentException("boom"));

            AtlasException exception = assertThrows(AtlasException.class, () -> module.processPreSourceExecution(session));

            assertEquals("boom", exception.getMessage());
            assertInstanceOf(IllegalArgumentException.class, exception.getCause());
        }
    }

    @Test
    void shouldThrowWhenProcessingPreTargetExecution() {
        QipGeneratedValueAtlasModule module = new QipGeneratedValueAtlasModule();
        AtlasInternalSession session = mock(AtlasInternalSession.class);

        AtlasException exception = assertThrows(AtlasException.class, () -> module.processPreTargetExecution(session));

        assertEquals("Module supports only source mode", exception.getMessage());
    }

    @Test
    void shouldReadSourceValueAndReplaceSourceFieldWithClonedConstantField() throws Exception {
        TestableQipGeneratedValueAtlasModule module =
                new TestableQipGeneratedValueAtlasModule("atlas:generated:test", "doc1");
        AtlasInternalSession session = mock(AtlasInternalSession.class, RETURNS_DEEP_STUBS);
        Map<String, Object> sourceProperties = new HashMap<>();
        GeneratedField sourceField = new GeneratedField();
        sourceField.setDocId("source-doc");
        sourceField.setPath("/generated");
        sourceField.setValue("old-value");

        sourceProperties.put("Atlas.GeneratedValue.doc1", "new-value");

        when(session.head().getSourceField()).thenReturn(sourceField);
        when(session.getSourceProperties()).thenReturn(sourceProperties);

        module.readSourceValue(session);

        ArgumentCaptor<Field> captor = ArgumentCaptor.forClass(Field.class);
        verify(session.head()).setSourceField(captor.capture());

        Field result = captor.getValue();
        assertInstanceOf(ConstantField.class, result);
        assertNotSame(sourceField, result);
        assertEquals("source-doc", result.getDocId());
        assertEquals("/generated", result.getPath());
        assertEquals("new-value", result.getValue());
    }

    @Test
    void shouldDoNothingInPostSourceExecution() {
        QipGeneratedValueAtlasModule module = new QipGeneratedValueAtlasModule();
        AtlasInternalSession session = mock(AtlasInternalSession.class);

        assertDoesNotThrow(() -> module.processPostSourceExecution(session));
    }

    @Test
    void shouldThrowWhenWritingTargetValue() {
        QipGeneratedValueAtlasModule module = new QipGeneratedValueAtlasModule();
        AtlasInternalSession session = mock(AtlasInternalSession.class);

        AtlasException exception = assertThrows(AtlasException.class, () -> module.writeTargetValue(session));

        assertEquals("Module supports only source mode", exception.getMessage());
    }

    @Test
    void shouldThrowWhenProcessingPostTargetExecution() {
        QipGeneratedValueAtlasModule module = new QipGeneratedValueAtlasModule();
        AtlasInternalSession session = mock(AtlasInternalSession.class);

        AtlasException exception = assertThrows(AtlasException.class, () -> module.processPostTargetExecution(session));

        assertEquals("Module supports only source mode", exception.getMessage());
    }

    @Test
    void shouldCloneFieldIntoConstantField() throws Exception {
        QipGeneratedValueAtlasModule module = new QipGeneratedValueAtlasModule();
        GeneratedField sourceField = new GeneratedField();
        sourceField.setDocId("doc1");
        sourceField.setPath("/generated");
        sourceField.setValue("value");

        Field cloned = module.cloneField(sourceField);

        assertInstanceOf(ConstantField.class, cloned);
        assertNotSame(sourceField, cloned);
        assertEquals("doc1", cloned.getDocId());
        assertEquals("/generated", cloned.getPath());
        assertEquals("value", cloned.getValue());
    }

    @Test
    void shouldCreateConstantField() {
        QipGeneratedValueAtlasModule module = new QipGeneratedValueAtlasModule();

        Field field = module.createField();

        assertInstanceOf(ConstantField.class, field);
    }

    private static void setValueGeneratorFactory(
            QipGeneratedValueAtlasModule module,
            ValueGeneratorFactory valueGeneratorFactory
    ) throws Exception {
        java.lang.reflect.Field field = QipGeneratedValueAtlasModule.class.getDeclaredField("valueGeneratorFactory");
        field.setAccessible(true);
        field.set(module, valueGeneratorFactory);
    }

    private static ValueGeneratorInfo valueGeneratorInfo(String name, List<String> parameters) throws Exception {
        if (ValueGeneratorInfo.class.isRecord()) {
            RecordComponent[] components = ValueGeneratorInfo.class.getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            Object[] values = new Object[components.length];

            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                values[i] = recordComponentValue(components[i].getName(), components[i].getType(), name, parameters);
            }

            Constructor<ValueGeneratorInfo> constructor = ValueGeneratorInfo.class.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return constructor.newInstance(values);
        }

        Constructor<?>[] constructors = ValueGeneratorInfo.class.getDeclaredConstructors();
        if (constructors.length == 0) {
            throw new IllegalStateException("Unable to instantiate ValueGeneratorInfo");
        }

        Constructor<?> constructor = constructors[0];
        Object[] values = new Object[constructor.getParameterCount()];
        Class<?>[] types = constructor.getParameterTypes();

        for (int i = 0; i < types.length; i++) {
            values[i] = defaultValue(types[i], name, parameters);
        }

        constructor.setAccessible(true);
        return (ValueGeneratorInfo) constructor.newInstance(values);
    }

    private static Object recordComponentValue(String componentName, Class<?> type, String name, List<String> parameters) {
        if ("name".equals(componentName)) {
            return name;
        }
        if ("parameters".equals(componentName)) {
            return parameterValue(type, parameters);
        }
        return defaultValue(type, name, parameters);
    }

    private static Object defaultValue(Class<?> type, String name, List<String> parameters) {
        if (type == String.class) {
            return name;
        }
        if (List.class.isAssignableFrom(type) || Iterable.class.isAssignableFrom(type)) {
            return parameters;
        }
        return parameterValue(type, parameters);
    }

    private static Object parameterValue(Class<?> type, List<String> parameters) {
        if (List.class.isAssignableFrom(type) || Iterable.class.isAssignableFrom(type)) {
            return parameters;
        }
        if (type.isArray() && type.getComponentType() == String.class) {
            return parameters.toArray(String[]::new);
        }
        if (type == int.class || type == Integer.class) {
            return 0;
        }
        if (type == long.class || type == Long.class) {
            return 0L;
        }
        if (type == boolean.class || type == Boolean.class) {
            return false;
        }
        return null;
    }

    private static class TestableQipGeneratedValueAtlasModule extends QipGeneratedValueAtlasModule {
        private final String uri;
        private final String docId;

        TestableQipGeneratedValueAtlasModule(String uri, String docId) {
            this.uri = uri;
            this.docId = docId;
        }

        @Override
        public String getUri() {
            return uri;
        }

        @Override
        public String getDocId() {
            return docId;
        }
    }
}
