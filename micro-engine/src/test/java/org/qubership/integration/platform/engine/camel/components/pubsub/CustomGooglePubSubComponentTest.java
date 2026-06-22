package org.qubership.integration.platform.engine.camel.components.pubsub;

import org.apache.camel.Endpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.PubSubTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CustomGooglePubSubComponentTest {

    private CustomGooglePubSubComponent component;

    @BeforeEach
    void setUp() {
        component = PubSubTestUtils.newComponent();
    }

    @Test
    void shouldCreateCustomEndpointAndCopyComponentSettingsWhenRemainingIsValid() throws Exception {
        component.setServiceAccountKey("classpath:service-account.json");
        component.setAuthenticate(false);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("maxMessagesPerPoll", 5);
        parameters.put("synchronousPull", true);

        Endpoint endpoint = component.createEndpoint(
                "google-pubsub:project-id:destination-name",
                "project-id:destination-name",
                parameters
        );

        assertInstanceOf(CustomGooglePubSubEndpoint.class, endpoint);
        CustomGooglePubSubEndpoint pubsubEndpoint = (CustomGooglePubSubEndpoint) endpoint;
        assertSame(component, pubsubEndpoint.getComponent());
        assertEquals("project-id", pubsubEndpoint.getProjectId());
        assertEquals("destination-name", pubsubEndpoint.getDestinationName());
        assertEquals("classpath:service-account.json", pubsubEndpoint.getServiceAccountKey());
        assertFalse(pubsubEndpoint.isAuthenticate());
        assertEquals(5, pubsubEndpoint.getMaxMessagesPerPoll());
        assertTrue(pubsubEndpoint.isSynchronousPull());
    }

    @Test
    void shouldThrowWhenRemainingDoesNotContainDestinationName() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                component.createEndpoint("google-pubsub:project-id", "project-id", new HashMap<>())
        );

        assertEquals(
                "Google PubSub Endpoint format \"projectId:destinationName[:subscriptionName]\"",
                exception.getMessage()
        );
    }
}
