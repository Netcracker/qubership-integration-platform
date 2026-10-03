package org.qubership.integration.platform.maven.plugin.domain.builders.chain;

import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTaskParameters;
import org.qubership.integration.platform.maven.plugin.mojos.ControlPlaneType;

import java.util.Optional;

import static org.qubership.integration.platform.maven.plugin.domain.services.MicroDomainResourcesBuildService.BUILD_CRS_TASK_PARAMETERS;

public final class ControlPlaneUtil {
    private ControlPlaneUtil() {}

    public static boolean enabled(ResourceBuildContext<?> context, ControlPlaneType controlPlaneType) {
        return Optional.ofNullable(context.getBuildCache().get(BUILD_CRS_TASK_PARAMETERS))
            .map(BuildCRsTaskParameters.class::cast)
            .map(BuildCRsTaskParameters::getControlPlaneType)
            .map(controlPlaneType::equals)
            .orElseThrow(() -> new RuntimeException("Control plane type is not specified"));
    }
}
