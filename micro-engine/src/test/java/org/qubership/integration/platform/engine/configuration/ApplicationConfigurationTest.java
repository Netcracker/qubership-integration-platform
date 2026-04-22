package org.qubership.integration.platform.engine.configuration;

import io.quarkus.test.component.QuarkusComponentTest;
import io.quarkus.test.component.TestConfigProperty;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusComponentTest
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
@TestConfigProperty(key = "application.name", value = "qip-engine")
@TestConfigProperty(key = "application.cloud_service_name", value = "qip-engine-v1")
@TestConfigProperty(key = "application.namespace", value = "local")
@TestConfigProperty(key = "application.default_integration_domain_name", value = "default")
@TestConfigProperty(key = "application.default_integration_domain_microservice_name", value = "qip-engine")
class ApplicationConfigurationTest {

    @Inject
    ApplicationConfiguration applicationConfiguration;

    @Test
    void shouldInjectConfigurationProperties() {
        assertEquals("qip-engine", applicationConfiguration.getMicroserviceName());
        assertEquals("qip-engine-v1", applicationConfiguration.getCloudServiceName());
        assertEquals("local", applicationConfiguration.getNamespace());
    }

    @Test
    void shouldReturnDeploymentNameWhenGetDeploymentNameIsCalled() {
        assertEquals("qip-engine-v1", applicationConfiguration.getDeploymentName());
    }
}
