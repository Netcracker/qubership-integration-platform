package org.qubership.integration.platform.engine.consul.updates.parsers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.ext.consul.KeyValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.kafka.systemmodel.CompiledLibraryUpdate;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class LibrariesUpdateParserTest {

    private LibrariesUpdateParser parser;

    @Mock
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        parser = new LibrariesUpdateParser();
        parser.objectMapper = objectMapper;
    }

    @Test
    void shouldReturnEmptyListWhenEntriesEmpty() {
        List<CompiledLibraryUpdate> result = parser.apply(List.of());

        assertEquals(Collections.emptyList(), result);
        verifyNoInteractions(objectMapper);
    }

    @Test
    void shouldReturnEmptyListWhenSingleEntryValueBlank() {
        KeyValue entry = entry("   ");

        List<CompiledLibraryUpdate> result = parser.apply(List.of(entry));

        assertEquals(Collections.emptyList(), result);
        verifyNoInteractions(objectMapper);
    }

    @Test
    void shouldReturnParsedLibrariesWhenSingleEntryContainsJson() throws Exception {
        String value = """
                [
                  {
                    "libraryId": "customer-profile-lib"
                  }
                ]
                """;
        List<CompiledLibraryUpdate> updates = List.of(
                mock(CompiledLibraryUpdate.class),
                mock(CompiledLibraryUpdate.class)
        );
        KeyValue entry = entry(value);

        when(objectMapper.readValue(
                eq(value),
                any(TypeReference.class)
        )).thenReturn(updates);

        List<CompiledLibraryUpdate> result = parser.apply(List.of(entry));

        assertSame(updates, result);
    }

    @Test
    void shouldThrowRuntimeExceptionWhenJsonProcessingFails() throws Exception {
        String value = "[invalid-json]";
        KeyValue entry = entry(value);
        JsonProcessingException cause = new JsonProcessingException("Invalid json") {
        };

        when(objectMapper.readValue(
                eq(value),
                any(TypeReference.class)
        )).thenThrow(cause);

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> parser.apply(List.of(entry))
        );

        assertSame(cause, exception.getCause());
    }

    @Test
    void shouldThrowRuntimeExceptionWhenMoreThanOneEntryProvided() {
        KeyValue firstEntry = entry("[]");
        KeyValue secondEntry = entry("[]");

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> parser.apply(List.of(firstEntry, secondEntry))
        );

        assertEquals(
                "Invalid number of library update values: 2",
                exception.getMessage()
        );
    }

    private static KeyValue entry(String value) {
        return new KeyValue().setValue(value);
    }
}
