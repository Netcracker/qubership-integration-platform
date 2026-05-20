package org.qubership.integration.platform.engine.testutils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opensearch.client.opensearch.generic.Request;
import org.qubership.integration.platform.engine.model.opensearch.OpenSearchFieldType;
import org.qubership.integration.platform.engine.opensearch.annotation.OpenSearchDocument;
import org.qubership.integration.platform.engine.opensearch.annotation.OpenSearchField;
import org.qubership.integration.platform.engine.opensearch.ism.model.actions.Action;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class OpenSearchTestUtils {

    public static class UnsupportedAction extends Action {
    }

    public static class BaseDocument {
        private String baseText;
    }

    public static class SupportedDocument extends BaseDocument {
        private int count;
        private Long total;
        private double ratio;
        private Float score;
        private boolean active;

        @OpenSearchField(type = OpenSearchFieldType.Keyword)
        private String keywordField;

        @OpenSearchField(type = OpenSearchFieldType.Date)
        private Instant createdAt;

        @OpenSearchField(type = OpenSearchFieldType.Object)
        private NestedDocument nested;
    }

    public static class NestedDocument {
        private String nestedText;
        private int nestedCount;
    }

    public static class UnsupportedDocument {
        private List<String> unsupported;
    }

    public static class EmptyDocument {
    }

    @OpenSearchDocument(documentNameProperty = "test.opensearch.init.success")
    public static class InitSuccessDocument {
        private String field1;
    }

    @OpenSearchDocument(documentNameProperty = "test.opensearch.blank.name")
    public static class BlankNameDocument {
        private String field1;
    }

    @OpenSearchDocument(documentNameProperty = "test.opensearch.unsupported.name")
    public static class UnsupportedInitDocument {
        private java.util.List<String> unsupported;
    }

    @SuppressWarnings("unchecked")
    public static <T> T invoke(
            Object target,
            String methodName,
            Class<?>[] parameterTypes,
            Object... args
    ) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        if (!method.trySetAccessible()) {
            throw new IllegalStateException("Failed to access method: " + methodName);
        }
        try {
            return (T) method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    public static void invokeVoid(
            Object target,
            String methodName,
            Class<?>[] parameterTypes,
            Object... args
    ) throws Exception {
        invoke(target, methodName, parameterTypes, args);
    }

    public static void assertJsonBodyEquals(ObjectMapper objectMapper, Request request, String expectedJson) throws Exception {
        JsonNode actual = objectMapper.readTree(request.getBody().orElseThrow().bodyAsString());
        JsonNode expected = objectMapper.readTree(expectedJson);
        assertEquals(expected, actual);
    }

    public static Map<String, Object> getMapping() {
        return Map.of(
                "properties", Map.of(
                        "field1", Map.of("type", "keyword")
                )
        );
    }
}
