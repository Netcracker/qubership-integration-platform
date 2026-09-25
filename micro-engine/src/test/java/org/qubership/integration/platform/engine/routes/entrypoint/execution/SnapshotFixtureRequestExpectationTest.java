package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SnapshotFixtureRequestExpectationTest {
    @Test
    void shouldDistinguishEmptyRoutingKeyFromMissingOrNullKeyWhenParsingExpectations() throws IOException {
        ObjectMapper mapper = ObjectMappers.getObjectMapper();

        SnapshotFixtureRequestExpectation empty = mapper.readValue("{\"key\":\"\"}", SnapshotFixtureRequestExpectation.class);
        SnapshotFixtureRequestExpectation missing = mapper.readValue("{}", SnapshotFixtureRequestExpectation.class);
        SnapshotFixtureRequestExpectation explicitNull = mapper.readValue("{\"key\":null}", SnapshotFixtureRequestExpectation.class);

        assertEquals("", empty.getKey());
        assertNull(missing.getKey());
        assertNull(explicitNull.getKey());
    }
}
