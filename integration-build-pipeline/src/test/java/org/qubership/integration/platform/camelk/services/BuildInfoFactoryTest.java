package org.qubership.integration.platform.camelk.services;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildInfoFactoryTest {
    private static final Instant TIMESTAMP = Instant.parse("2026-01-01T00:00:00Z");

    private final BuildInfoFactory factory = new BuildInfoFactory(context -> "build-name");

    @Test
    void usesTheSuppliedTimestampInsteadOfTheClock() {
        BuildInfo first = factory.createBuildInfo(ResourceBuildOptions.builder().build(), "tester", TIMESTAMP);
        BuildInfo second = factory.createBuildInfo(ResourceBuildOptions.builder().build(), "tester", TIMESTAMP);

        assertEquals(TIMESTAMP, first.getTimestamp());
        assertEquals(TIMESTAMP, second.getTimestamp());
    }
}
