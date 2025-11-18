package org.qubership.integration.platform.engine.component;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.component.profile.DeploymentTestProfile;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingService;
import org.qubership.integration.platform.engine.testutils.DeploymentUtils;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.RouteTestHelpers;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(DeploymentTestProfile.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class DeploymentProcessingServiceComponentTest {

    @Inject
    DeploymentProcessingService deploymentProcessingService;

    @Inject
    CamelContext camelContext;

    @Test
    void whenConditionTrueThenIf() throws Exception {
        String xmlRoutes = DeploymentUtils.loadXml("routes/choice.xml");

        var deploymentUpdate = DeploymentUtils.deploymentFromXml(xmlRoutes);
        deploymentProcessingService.deploy(deploymentUpdate, true);

        String chainEntry = RouteTestHelpers.entryFromUri(camelContext, xmlRoutes);

        ProducerTemplate tpl = camelContext.createProducerTemplate();
        org.apache.camel.Exchange response = tpl.request(chainEntry, ex -> {
            ex.setProperty("condition", true);
            ex.getMessage().setBody("ignored");
        });

        String responseBody = response.getMessage().getBody(String.class);
        assertEquals("If", responseBody);

        deploymentProcessingService.undeploy(deploymentUpdate);
    }

    @Test
    void whenConditionFalseThenElse() throws Exception {
        String xmlRoutes = DeploymentUtils.loadXml("routes/choice.xml");

        var deploymentUpdate = DeploymentUtils.deploymentFromXml(xmlRoutes);
        deploymentProcessingService.deploy(deploymentUpdate, true);

        String chainEntry = RouteTestHelpers.entryFromUri(camelContext, xmlRoutes);

        ProducerTemplate tpl = camelContext.createProducerTemplate();
        org.apache.camel.Exchange response = tpl.request(chainEntry, ex -> {
            ex.setProperty("condition", false);
            ex.getMessage().setBody("ignored");
        });

        String responseBody = response.getMessage().getBody(String.class);
        assertEquals("Else", responseBody);

        deploymentProcessingService.undeploy(deploymentUpdate);
    }
}
