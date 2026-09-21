package org.qubership.integration.platform.maven.plugin.domain.services;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.options.ContainerOptions;
import org.qubership.integration.platform.camelk.model.options.HealthOptions;
import org.qubership.integration.platform.camelk.model.options.IntegrationsConfigurationOptions;
import org.qubership.integration.platform.camelk.model.options.JvmOptions;
import org.qubership.integration.platform.camelk.model.options.MonitoringOptions;
import org.qubership.integration.platform.camelk.model.options.MountOptions;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.model.options.ServiceOptions;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTaskParameters;
import org.qubership.integration.platform.maven.plugin.mojos.BuildCRsOptions;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class ResourceBuildOptionsFactoryTest {

    private static final String DEFAULT_IMAGE = "qip/micro-engine:test";

    private final ResourceBuildOptionsFactory factory = new ResourceBuildOptionsFactory(DEFAULT_IMAGE);

    @Test
    void namesTheResourcesAfterTheDomainAndCarriesTheMojoOptionsOver() {
        ContainerOptions container = ContainerOptions.builder().image("chosen/image:1").build();
        HealthOptions health = new HealthOptions();
        JvmOptions jvm = new JvmOptions();
        MonitoringOptions monitoring = new MonitoringOptions();
        ServiceOptions service = new ServiceOptions();
        MountOptions mount = new MountOptions();
        IntegrationsConfigurationOptions integrations = new IntegrationsConfigurationOptions();
        Map<String, String> environment = Map.of("LOG_LEVEL", "DEBUG");
        BuildCRsOptions options = BuildCRsOptions.builder()
            .replicas(3)
            .container(container)
            .health(health)
            .jvm(jvm)
            .monitoring(monitoring)
            .service(service)
            .mount(mount)
            .environment(environment)
            .integrations(integrations)
            .serviceAccount("qip")
            .build();

        ResourceBuildOptions result = factory.createResourceBuildOptions("orders", parameters(options));

        assertEquals("orders", result.getName());
        assertEquals(3, result.getReplicas());
        assertEquals(container, result.getContainer());
        assertSame(health, result.getHealth());
        assertSame(jvm, result.getJvm());
        assertSame(monitoring, result.getMonitoring());
        assertSame(service, result.getService());
        assertSame(mount, result.getMount());
        assertSame(integrations, result.getIntegrations());
        assertEquals(environment, result.getEnvironment());
        assertEquals("qip", result.getServiceAccount());
    }

    @Test
    void fallsBackToTheMojoOptionDefaults() {
        ResourceBuildOptions result =
            factory.createResourceBuildOptions("default", parameters(new BuildCRsOptions()));

        assertEquals(1, result.getReplicas());
        assertEquals("default", result.getServiceAccount());
        assertEquals(Map.of(), result.getEnvironment());
    }

    /** Without an image the resources are unusable, so the configured one fills the gap. */
    @Test
    void usesTheConfiguredImageWhenTheMojoLeavesItUnset() {
        ResourceBuildOptions result =
            factory.createResourceBuildOptions("default", parameters(new BuildCRsOptions()));

        assertEquals(DEFAULT_IMAGE, result.getContainer().getImage());
    }

    @Test
    void keepsTheImageTheMojoSets() {
        BuildCRsOptions options = BuildCRsOptions.builder()
            .container(ContainerOptions.builder().image("chosen/image:1").build())
            .build();

        ResourceBuildOptions result = factory.createResourceBuildOptions("orders", parameters(options));

        assertEquals("chosen/image:1", result.getContainer().getImage());
    }

    /** toBuilder() copies the rest of the container, so filling the image must not drop the others. */
    @Test
    void keepsTheOtherContainerOptionsWhenItFillsInTheImage() {
        ContainerOptions container = ContainerOptions.builder()
            .runAsUser(1000)
            .readOnlyRootFilesystem(false)
            .build();
        BuildCRsOptions options = BuildCRsOptions.builder().container(container).build();

        ResourceBuildOptions result = factory.createResourceBuildOptions("orders", parameters(options));

        assertEquals(DEFAULT_IMAGE, result.getContainer().getImage());
        assertEquals(1000, result.getContainer().getRunAsUser());
        assertFalse(result.getContainer().isReadOnlyRootFilesystem());
    }

    @Test
    void appliesTheSameDefaultsWhicheverWayTheMojoOptionsAreCreated() {
        assertEquals(new BuildCRsOptions(), BuildCRsOptions.builder().build());
    }

    private static BuildCRsTaskParameters parameters(BuildCRsOptions options) {
        return BuildCRsTaskParameters.builder().options(options).build();
    }
}
