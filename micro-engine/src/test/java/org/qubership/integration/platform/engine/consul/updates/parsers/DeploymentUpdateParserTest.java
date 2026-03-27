package org.qubership.integration.platform.engine.consul.updates.parsers;

import io.vertx.ext.consul.KeyValue;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class DeploymentUpdateParserTest {

    private final DeploymentUpdateParser parser = new DeploymentUpdateParser();

    @Test
    void shouldReturnZeroWhenEntriesEmpty() {
        Long result = parser.apply(List.of());

        assertEquals(0L, result);
    }

    @Test
    void shouldReturnZeroWhenSingleEntryValueNull() {
        KeyValue entry = entry(null);

        Long result = parser.apply(List.of(entry));

        assertEquals(0L, result);
    }

    @Test
    void shouldReturnZeroWhenSingleEntryValueBlank() {
        KeyValue entry = entry("   ");

        Long result = parser.apply(List.of(entry));

        assertEquals(0L, result);
    }

    @Test
    void shouldReturnParsedLongWhenSingleEntryValueContainsNumber() {
        KeyValue entry = entry("42");

        Long result = parser.apply(List.of(entry));

        assertEquals(42L, result);
    }

    @Test
    void shouldThrowNumberFormatExceptionWhenSingleEntryValueNotNumber() {
        KeyValue entry = entry("abc");

        assertThrows(
                NumberFormatException.class,
                () -> parser.apply(List.of(entry))
        );
    }

    @Test
    void shouldThrowRuntimeExceptionWhenMoreThanOneEntryProvided() {
        KeyValue firstEntry = entry("1");
        KeyValue secondEntry = entry("2");

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> parser.apply(List.of(firstEntry, secondEntry))
        );

        assertEquals(
                "Invalid number of deployment update entries: 2",
                exception.getMessage()
        );
    }

    private static KeyValue entry(String value) {
        return new KeyValue().setValue(value);
    }
}
