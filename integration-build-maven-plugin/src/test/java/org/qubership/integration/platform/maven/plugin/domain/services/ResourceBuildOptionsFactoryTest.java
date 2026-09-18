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
import static org.junit.jupiter.api.Assertions.assertSame;

class ResourceBuildOptionsFactoryTest {

    private final ResourceBuildOptionsFactory factory = new ResourceBuildOptionsFactory();

    @Test
    void namesTheResourcesAfterTheDomainAndCarriesTheMojoOptionsOver() {
        ContainerOptions container = new ContainerOptions();
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
        assertSame(container, result.getContainer());
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

    @Test
    void appliesTheSameDefaultsWhicheverWayTheMojoOptionsAreCreated() {
        assertEquals(new BuildCRsOptions(), BuildCRsOptions.builder().build());
    }

    private static BuildCRsTaskParameters parameters(BuildCRsOptions options) {
        return BuildCRsTaskParameters.builder().options(options).build();
    }
}
