package org.qubership.integration.platform.maven.plugin.domain.builders.chain;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTaskParameters;
import org.qubership.integration.platform.maven.plugin.mojos.ControlPlaneType;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.maven.plugin.domain.services.MicroDomainResourcesBuildService.BUILD_CRS_TASK_PARAMETERS;

class ControlPlaneUtilTest {

    @Test
    void enablesABuilderThatAsksForTheSelectedControlPlane() {
        assertTrue(ControlPlaneUtil.enabled(contextFor(ControlPlaneType.ISTIO), ControlPlaneType.ISTIO));
        assertTrue(ControlPlaneUtil.enabled(contextFor(ControlPlaneType.CORE), ControlPlaneType.CORE));
    }

    @Test
    void disablesABuilderThatAsksForAnotherControlPlane() {
        assertFalse(ControlPlaneUtil.enabled(contextFor(ControlPlaneType.ISTIO), ControlPlaneType.CORE));
        assertFalse(ControlPlaneUtil.enabled(contextFor(ControlPlaneType.CORE), ControlPlaneType.ISTIO));
    }

    /**
     * Returning false here would drop every route resource from the output without a word, which is
     * the failure this method exists to rule out. Both ways of losing the value get the same message.
     */
    @Test
    void failsWhenTheBuildNeverRecordedItsParameters() {
        ResourceBuildContext<Void> context =
            ResourceBuildContext.create(BuildInfo.builder().build(), IntegrationServiceCatalog.EMPTY);

        RuntimeException exception = assertThrows(RuntimeException.class,
            () -> ControlPlaneUtil.enabled(context, ControlPlaneType.ISTIO));

        assertTrue(exception.getMessage().contains("Control plane type is not specified"));
    }

    @Test
    void failsWhenTheRecordedParametersCarryNoControlPlaneType() {
        ResourceBuildContext<Void> context = contextFor(null);

        RuntimeException exception = assertThrows(RuntimeException.class,
            () -> ControlPlaneUtil.enabled(context, ControlPlaneType.ISTIO));

        assertTrue(exception.getMessage().contains("Control plane type is not specified"));
    }

    private static ResourceBuildContext<Void> contextFor(ControlPlaneType controlPlaneType) {
        ResourceBuildContext<Void> context =
            ResourceBuildContext.create(BuildInfo.builder().build(), IntegrationServiceCatalog.EMPTY);
        context.getBuildCache().put(BUILD_CRS_TASK_PARAMETERS,
            BuildCRsTaskParameters.builder().controlPlaneType(controlPlaneType).build());
        return context;
    }
}
