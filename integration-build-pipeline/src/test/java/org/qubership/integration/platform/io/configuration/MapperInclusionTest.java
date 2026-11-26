package org.qubership.integration.platform.io.configuration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MapperInclusionTest {

    @Test
    void nonNullInclusionOmitsNullMapValues() {
        Map<String, Object> values = new HashMap<>();
        values.put("key", null);

        String serialized = JsonMapper.builder()
                .changeDefaultPropertyInclusion(MapperInclusion::nonNull)
                .build()
                .writeValueAsString(values);

        assertThat(serialized).doesNotContain("\"key\"");
    }

    @Test
    void primaryMapperOmitsNullMapValues() {
        Map<String, Object> values = new HashMap<>();
        values.put("key", null);

        String serialized = new ObjectMapperAutoConfiguration().primaryObjectMapper().writeValueAsString(values);

        assertThat(serialized).doesNotContain("\"key\"");
    }

    @Test
    void nonEmptyInclusionOmitsNullMapValues() {
        Map<String, Object> values = new HashMap<>();
        values.put("key", null);

        String serialized = JsonMapper.builder()
                .changeDefaultPropertyInclusion(MapperInclusion::nonEmpty)
                .build()
                .writeValueAsString(values);

        assertThat(serialized).doesNotContain("\"key\"");
    }
}
