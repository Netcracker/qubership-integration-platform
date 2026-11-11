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
@TestConfigProperty(key = "application.name", value = "cip-engine")
@TestConfigProperty(key = "application.cloud_service_name", value = "cip-engine-v1")
@TestConfigProperty(key = "application.namespace", value = "local")
@TestConfigProperty(key = "application.default_integration_domain_name", value = "default")
@TestConfigProperty(key = "application.default_integration_domain_microservice_name", value = "cip-engine")
class ApplicationConfigurationTest {

    @Inject
    ApplicationConfiguration applicationConfiguration;

    @Test
    void shouldInjectConfigurationProperties() {
        assertEquals("cip-engine", applicationConfiguration.getMicroserviceName());
        assertEquals("cip-engine-v1", applicationConfiguration.getCloudServiceName());
        assertEquals("local", applicationConfiguration.getNamespace());
    }

    @Test
    void shouldReturnDeploymentNameWhenGetDeploymentNameIsCalled() {
        assertEquals("cip-engine-v1", applicationConfiguration.getDeploymentName());
    }
}
