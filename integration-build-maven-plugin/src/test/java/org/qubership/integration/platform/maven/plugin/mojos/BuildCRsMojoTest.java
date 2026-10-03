package org.qubership.integration.platform.maven.plugin.mojos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuildCRsMojoTest {
    private static final Instant TIMESTAMP = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void readsEpochSeconds() {
        assertEquals(TIMESTAMP, BuildCRsMojo.parseOutputTimestamp("1767225600"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-01-01T00:00:00Z", "2026-01-01T03:00:00+03:00", " 2026-01-01T00:00:00Z "})
    void readsAnIsoInstant(String value) {
        assertEquals(TIMESTAMP, BuildCRsMojo.parseOutputTimestamp(value));
    }

    /** Projects keep a placeholder in the property until they opt in, so these must fall back to the clock. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "a", "0"})
    void takesTheCurrentTimeWhenUnset(String value) {
        Instant before = Instant.now();

        Instant timestamp = BuildCRsMojo.parseOutputTimestamp(value);

        assertFalse(timestamp.isBefore(before));
    }

    @Test
    void rejectsAValueThatIsNeitherFormat() {
        assertThrows(DateTimeParseException.class, () -> BuildCRsMojo.parseOutputTimestamp("yesterday"));
    }
}
