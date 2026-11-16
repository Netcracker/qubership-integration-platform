package org.qubership.integration.platform.runtime.catalog.configuration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.service.variables.secrets.KubeSecretSerializer;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class VariablesYamlMapperTest {
    private static YAMLMapper mapper() {
        return new MapperAutoConfiguration().variablesYamlMapper(mock(KubeSecretSerializer.class));
    }

    private static Map<String, String> values() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("a", ".inf");
        values.put("b", "1.10");
        values.put("c", "0x1F");
        values.put("d", "plain");
        return values;
    }

    @Test
    void roundTripsNumberLikeValues() throws Exception {
        YAMLMapper mapper = mapper();
        String text = mapper.writeValueAsString(values());
        assertEquals(values(), mapper.readValue(text, new TypeReference<Map<String, String>>() {}));
    }

    @Test
    void minimizesQuotes() throws Exception {
        assertTrue(mapper().writeValueAsString(values()).contains("d: plain"));
    }
}
