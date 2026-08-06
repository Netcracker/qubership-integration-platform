package org.qubership.integration.platform.runtime.catalog.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.BadRequestException;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;

import java.util.Optional;

import static java.util.Objects.isNull;

/**
 * How many environments a service may hold, driven by {@link IntegrationSystemType#maxEnvironments()}. The REST create
 * path and both import paths share it, so all of them reject an over-populated service alike.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EnvironmentLimitUtils {

    // Only a bounded type ever reaches this message, and every bounded type allows exactly one environment.
    private static final String OVER_LIMIT_MESSAGE =
            "Service '%s' (id %s) is %s and accepts at most %d environment, but %d were given.";

    /**
     * @param environmentCount the count the service would end up with, not the count it has now
     */
    public static void validate(IntegrationSystem system, int environmentCount) {
        violation(system, environmentCount).ifPresent(message -> {
            throw new BadRequestException(message + " Remove the extra environments and retry.");
        });
    }

    /**
     * The reason {@link #validate} would refuse the service, or empty when it would not. The export path reads it
     * rather than the throwing form: a row that already violates the limit still has to be extractable.
     *
     * @param environmentCount the count the service would end up with, not the count it has now
     */
    public static Optional<String> violation(IntegrationSystem system, int environmentCount) {
        IntegrationSystemType systemType = system.getIntegrationSystemType();
        // The column is nullable, and a typeless row has no limit to compare against.
        if (isNull(systemType)) {
            return Optional.empty();
        }
        int maxEnvironments = systemType.maxEnvironments();
        if (environmentCount <= maxEnvironments) {
            return Optional.empty();
        }
        return Optional.of(String.format(OVER_LIMIT_MESSAGE,
                system.getName(),
                system.getId(),
                systemType.name().toLowerCase(),
                maxEnvironments,
                environmentCount));
    }
}
