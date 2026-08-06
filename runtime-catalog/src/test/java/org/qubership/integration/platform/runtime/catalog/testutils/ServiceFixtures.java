package org.qubership.integration.platform.runtime.catalog.testutils;

import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;

import java.util.ArrayList;
import java.util.List;

/** One stored service, shared by the controller and the import tests that all assert against its type and limits. */
public final class ServiceFixtures {

    public static final String SYSTEM_ID = "system-1";
    public static final String SYSTEM_NAME = "Test service";

    private ServiceFixtures() {
    }

    /**
     * A service of {@code type} holding {@code environmentCount} environments.
     *
     * <p>Every environment gets an empty label list, the way a database-loaded {@code @ElementCollection} comes back.
     * {@code Environment.equals} compares two null lists by iterating one of them, so a null there fails on merge.
     */
    public static IntegrationSystem systemWith(IntegrationSystemType type, int environmentCount) {
        List<Environment> environments = new ArrayList<>();
        for (int i = 0; i < environmentCount; i++) {
            Environment environment = new Environment();
            environment.setId("environment-" + (i + 1));
            environment.setLabels(new ArrayList<>());
            environments.add(environment);
        }
        return IntegrationSystem.builder()
                .id(SYSTEM_ID)
                .name(SYSTEM_NAME)
                .integrationSystemType(type)
                .environments(environments)
                .build();
    }
}
