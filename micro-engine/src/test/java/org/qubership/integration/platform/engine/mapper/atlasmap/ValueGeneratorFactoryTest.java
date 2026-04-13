package org.qubership.integration.platform.engine.mapper.atlasmap;

import io.atlasmap.api.AtlasSession;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ValueGeneratorFactoryTest {

    private final ValueGeneratorFactory factory = new ValueGeneratorFactory();

    @Test
    void shouldThrowWhenGeneratorNameIsNull() {
        Exception exception = assertThrows(
                Exception.class,
                () -> factory.getValueGenerator(null, List.of())
        );

        assertEquals("Value generator name is null", exception.getMessage());
    }

    @Test
    void shouldThrowWhenGeneratorNameIsUnsupported() {
        Exception exception = assertThrows(
                Exception.class,
                () -> factory.getValueGenerator("unknownGenerator", List.of())
        );

        assertEquals("Unsupported value generator: unknownGenerator", exception.getMessage());
    }

    @Test
    void shouldReturnUuidGenerator() throws Exception {
        Function<AtlasSession, String> generator = factory.getValueGenerator("generateUUID", List.of());

        String first = generator.apply(null);
        String second = generator.apply(null);

        assertInstanceOf(String.class, first);
        assertInstanceOf(String.class, second);
        assertNotEquals(first, second);
    }

    @Test
    void shouldReturnTimestampGeneratorForCurrentDate() throws Exception {
        Function<AtlasSession, String> generator =
                factory.getValueGenerator("currentDate", List.of("true", "yyyyMMdd"));

        assertInstanceOf(TimestampGenerator.class, generator);
    }

    @Test
    void shouldReturnTimestampGeneratorForCurrentTime() throws Exception {
        Function<AtlasSession, String> generator =
                factory.getValueGenerator("currentTime", List.of("false", "HH:mm:ss"));

        assertInstanceOf(TimestampGenerator.class, generator);
    }

    @Test
    void shouldReturnTimestampGeneratorForCurrentDateTime() throws Exception {
        Function<AtlasSession, String> generator =
                factory.getValueGenerator("currentDateTime", List.of("false", "yyyy-MM-dd HH:mm:ss"));

        assertInstanceOf(TimestampGenerator.class, generator);
    }
}
