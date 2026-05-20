package org.qubership.integration.platform.engine.consul.updates.parsers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.InjectMock;
import io.quarkus.test.component.QuarkusComponentTest;
import io.quarkus.test.component.TestConfigProperty;
import io.smallrye.common.annotation.Identifier;
import io.vertx.ext.consul.KeyValue;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.service.debugger.RuntimePropertiesException;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@QuarkusComponentTest
@TestConfigProperty(
        key = "consul.keys.runtime-configurations",
        value = "/runtime-configurations"
)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ChainRuntimePropertiesUpdateParserTest {

    @Inject
    ChainRuntimePropertiesUpdateParser parser;

    @InjectMock
    @Identifier("jsonMapper")
    ObjectMapper objectMapper;

    @Test
    void shouldReturnRuntimePropertiesMappedByChainIdWhenEntriesValid() throws Exception {
        KeyValue firstEntry = entry(
                "root/runtime-configurations/chains/e3f499f4-d36f-4984-b7d1-a76ed6879eeb",
                "{\"maskingEnabled\":true}"
        );
        KeyValue secondEntry = entry(
                "root/runtime-configurations/chains/c1fc486d-0e83-48b5-a866-091756c3ff7f",
                "{\"dptEventsEnabled\":true}"
        );

        ChainRuntimeProperties firstProperties = ChainRuntimeProperties.builder()
                .maskingEnabled(true)
                .build();
        ChainRuntimeProperties secondProperties = ChainRuntimeProperties.builder()
                .dptEventsEnabled(true)
                .build();

        when(objectMapper.readValue(firstEntry.getValue(), ChainRuntimeProperties.class))
                .thenReturn(firstProperties);
        when(objectMapper.readValue(secondEntry.getValue(), ChainRuntimeProperties.class))
                .thenReturn(secondProperties);

        Map<String, ChainRuntimeProperties> result = parser.apply(List.of(firstEntry, secondEntry));

        assertEquals(2, result.size());
        assertSame(firstProperties, result.get("e3f499f4-d36f-4984-b7d1-a76ed6879eeb"));
        assertSame(secondProperties, result.get("c1fc486d-0e83-48b5-a866-091756c3ff7f"));
    }

    @Test
    void shouldThrowRuntimePropertiesExceptionWhenKeyIsInvalid() {
        KeyValue entry = entry(
                "root/other-config/chains/e3f499f4-d36f-4984-b7d1-a76ed6879eeb",
                "{\"maskingEnabled\":true}"
        );

        RuntimePropertiesException exception = assertThrows(
                RuntimePropertiesException.class,
                () -> parser.apply(List.of(entry))
        );

        assertEquals(
                "Failed to parse response, invalid 'key' field: root/other-config/chains/e3f499f4-d36f-4984-b7d1-a76ed6879eeb",
                exception.getMessage()
        );
        verifyNoInteractions(objectMapper);
    }

    @Test
    void shouldThrowRuntimePropertiesExceptionWhenAnyEntryCannotBeDeserialized() throws Exception {
        KeyValue invalidEntry = entry(
                "root/runtime-configurations/chains/e3f499f4-d36f-4984-b7d1-a76ed6879eeb",
                "invalid-json"
        );
        KeyValue validEntry = entry(
                "root/runtime-configurations/chains/c1fc486d-0e83-48b5-a866-091756c3ff7f",
                "{\"maskingEnabled\":false}"
        );

        ChainRuntimeProperties validProperties = ChainRuntimeProperties.builder()
                .maskingEnabled(false)
                .build();

        when(objectMapper.readValue(invalidEntry.getValue(), ChainRuntimeProperties.class))
                .thenThrow(new IllegalArgumentException("Invalid json"));
        when(objectMapper.readValue(validEntry.getValue(), ChainRuntimeProperties.class))
                .thenReturn(validProperties);

        RuntimePropertiesException exception = assertThrows(
                RuntimePropertiesException.class,
                () -> parser.apply(List.of(invalidEntry, validEntry))
        );

        assertEquals(
                "Failed to deserialize consul response for one or more chains",
                exception.getMessage()
        );
    }

    @Test
    void shouldReturnEmptyMapWhenEntriesEmpty() {
        Map<String, ChainRuntimeProperties> result = parser.apply(List.of());

        assertTrue(result.isEmpty());
        verifyNoInteractions(objectMapper);
    }

    private static KeyValue entry(String key, String value) {
        return new KeyValue()
                .setKey(key)
                .setValue(value);
    }
}
