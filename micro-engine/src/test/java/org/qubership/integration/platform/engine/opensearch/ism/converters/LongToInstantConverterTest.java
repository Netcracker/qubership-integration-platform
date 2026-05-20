package org.qubership.integration.platform.engine.opensearch.ism.converters;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class LongToInstantConverterTest {

    private final LongToInstantConverter converter = new LongToInstantConverter();

    @ParameterizedTest
    @MethodSource("epochMillis")
    void shouldConvertEpochMillisToInstant(Long value) {
        Instant result = converter.convert(value);

        assertEquals(Instant.ofEpochMilli(value), result);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenValueIsNull() {
        assertThrows(NullPointerException.class, () -> converter.convert(null));
    }

    private static Stream<Long> epochMillis() {
        return Stream.of(
                0L,
                1L,
                -1L,
                1_710_000_000_000L
        );
    }
}
