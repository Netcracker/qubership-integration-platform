package org.qubership.integration.platform.engine.mapper.atlasmap;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ValueGeneratorInfoDecoderTest {

    @Test
    void shouldDecodeNameAndParameters() {
        ValueGeneratorInfo result = ValueGeneratorInfoDecoder.decode(
                "atlas:cip:generated?name=currentDateTime&parameter=true&parameter=yyyyMMdd&parameter=ru_RU"
        );

        assertEquals("currentDateTime", result.name());
        assertEquals(List.of("true", "yyyyMMdd", "ru_RU"), result.parameters());
    }

    @Test
    void shouldDecodeOnlyNameWhenParametersAreAbsent() {
        ValueGeneratorInfo result = ValueGeneratorInfoDecoder.decode(
                "atlas:cip:generated?name=generateUUID"
        );

        assertEquals("generateUUID", result.name());
        assertEquals(List.of(), result.parameters());
    }

    @Test
    void shouldDecodeOnlyParametersWhenNameIsAbsent() {
        ValueGeneratorInfo result = ValueGeneratorInfoDecoder.decode(
                "atlas:cip:generated?parameter=true&parameter=yyyyMMdd"
        );

        assertNull(result.name());
        assertEquals(List.of("true", "yyyyMMdd"), result.parameters());
    }

    @Test
    void shouldKeepLastNameWhenNameParameterRepeated() {
        ValueGeneratorInfo result = ValueGeneratorInfoDecoder.decode(
                "atlas:cip:generated?name=generateUUID&name=currentDate&parameter=UTC"
        );

        assertEquals("currentDate", result.name());
        assertEquals(List.of("UTC"), result.parameters());
    }

    @Test
    void shouldIgnoreUnsupportedQueryParameters() {
        ValueGeneratorInfo result = ValueGeneratorInfoDecoder.decode(
                "atlas:cip:generated?foo=bar&name=currentTime&x=1&parameter=HH:mm:ss"
        );

        assertEquals("currentTime", result.name());
        assertEquals(List.of("HH:mm:ss"), result.parameters());
    }

    @Test
    void shouldReturnEmptyInfoWhenQueryIsAbsent() {
        ValueGeneratorInfo result = ValueGeneratorInfoDecoder.decode("atlas:cip:generated");

        assertNull(result.name());
        assertEquals(List.of(), result.parameters());
    }
}
