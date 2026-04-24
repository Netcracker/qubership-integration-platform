package org.qubership.integration.platform.engine.configuration.opensearch;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.OpenSearchTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class OpenSearchInitializerMappingTest {

    private final OpenSearchInitializer initializer = new OpenSearchInitializer();

    @Test
    void shouldReturnEmptyIndexMapWhenClassIsNull() throws Exception {
        Map<String, Object> result = OpenSearchTestUtils.invoke(
                initializer,
                "getIndexMap",
                new Class<?>[]{Class.class},
                new Object[]{null}
        );

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldBuildIndexMapIncludingInheritedPrimitiveAndAnnotatedFields() throws Exception {
        Map<String, Object> result = OpenSearchTestUtils.invoke(
                initializer,
                "getIndexMap",
                new Class<?>[]{Class.class},
                OpenSearchTestUtils.SupportedDocument.class
        );

        assertEquals("text", map(result.get("baseText")).get("type"));
        assertEquals("integer", map(result.get("count")).get("type"));
        assertEquals("long", map(result.get("total")).get("type"));
        assertEquals("double", map(result.get("ratio")).get("type"));
        assertEquals("float", map(result.get("score")).get("type"));
        assertEquals("boolean", map(result.get("active")).get("type"));
        assertEquals("keyword", map(result.get("keywordField")).get("type"));

        Map<String, Object> createdAt = map(result.get("createdAt"));
        assertEquals("date", createdAt.get("type"));
        assertEquals("date_optional_time||epoch_millis", createdAt.get("format"));

        Map<String, Object> nestedObject = map(result.get("nested"));
        assertEquals("object", nestedObject.get("type"));

        Map<String, Object> nestedProperties = map(nestedObject.get("properties"));
        assertEquals("text", map(nestedProperties.get("nestedText")).get("type"));
        assertEquals("integer", map(nestedProperties.get("nestedCount")).get("type"));
    }

    @Test
    void shouldThrowExceptionWhenFieldTypeIsUnsupportedAndNotAnnotated() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> OpenSearchTestUtils.invoke(
                        initializer,
                        "getIndexMap",
                        new Class<?>[]{Class.class},
                        OpenSearchTestUtils.UnsupportedDocument.class
                )
        );

        assertTrue(exception.getMessage().contains("Unsupported type"));
        assertTrue(exception.getMessage().contains("unsupported"));
    }

    @Test
    void shouldBuildIndexMapSourceWithDefaultFlagsAndProperties() throws Exception {
        Map<String, Object> result = OpenSearchTestUtils.invoke(
                initializer,
                "getIndexMapSource",
                new Class<?>[]{Class.class},
                OpenSearchTestUtils.SupportedDocument.class
        );

        assertEquals(false, result.get("dynamic"));
        assertEquals(false, result.get("date_detection"));
        assertEquals(false, result.get("numeric_detection"));
        assertTrue(result.containsKey("properties"));

        Map<String, Object> propertiesMap = map(result.get("properties"));
        assertTrue(propertiesMap.containsKey("baseText"));
        assertTrue(propertiesMap.containsKey("nested"));
    }

    @Test
    void shouldBuildIndexMapSourceWithoutPropertiesWhenClassHasNoFields() throws Exception {
        Map<String, Object> result = OpenSearchTestUtils.invoke(
                initializer,
                "getIndexMapSource",
                new Class<?>[]{Class.class},
                OpenSearchTestUtils.EmptyDocument.class
        );

        assertEquals(false, result.get("dynamic"));
        assertEquals(false, result.get("date_detection"));
        assertEquals(false, result.get("numeric_detection"));
        assertFalse(result.containsKey("properties"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
