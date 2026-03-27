package org.qubership.integration.platform.engine.camel.components.kafka;

import org.apache.camel.component.kafka.KafkaConfiguration;
import org.apache.camel.component.kafka.KafkaEndpoint;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.jsse.SSLContextParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class KafkaCustomComponentTest {

    private KafkaCustomComponent component;

    @BeforeEach
    void setUp() {
        component = spy(new KafkaCustomComponent());
        component.setCamelContext(new DefaultCamelContext());
        component.setConfiguration(new KafkaConfiguration());
    }

    @Test
    void shouldThrowWhenRemainingTopicEmpty() {
        assertThrows(IllegalArgumentException.class, () ->
                component.createEndpoint("kafka-custom:", "", new HashMap<>())
        );

        assertThrows(IllegalArgumentException.class, () ->
                component.createEndpoint("kafka-custom:", null, new HashMap<>())
        );
    }

    @Test
    void shouldCreateEndpointAndSetTopicFromRemainingWhenNotProvidedInParameters() throws Exception {
        KafkaEndpoint ep = component.createEndpoint(
                "kafka-custom:topicA",
                "topicA",
                new HashMap<>()
        );

        assertNotNull(ep);
        assertInstanceOf(KafkaCustomEndpoint.class, ep);

        KafkaConfiguration cfg = ep.getConfiguration();
        assertNotNull(cfg);
        assertEquals("topicA", cfg.getTopic());
    }

    @Test
    void shouldNotOverrideTopicWhenAlreadySetInComponentConfiguration() throws Exception {
        KafkaConfiguration base = new KafkaConfiguration();
        base.setTopic("explicitTopic");
        component.setConfiguration(base);

        KafkaEndpoint ep = component.createEndpoint(
                "kafka-custom:topicA",
                "topicA",
                new HashMap<>()
        );

        assertEquals("explicitTopic", ep.getConfiguration().getTopic());
    }

    @Test
    void shouldSetGlobalSslContextParametersWhenEndpointSslContextNotSet() throws Exception {
        SSLContextParameters global = new SSLContextParameters();
        doReturn(global).when(component).retrieveGlobalSslContextParameters();

        KafkaEndpoint ep = component.createEndpoint(
                "kafka-custom:topicA",
                "topicA",
                new HashMap<>()
        );

        assertSame(global, ep.getConfiguration().getSslContextParameters());
        verify(component).retrieveGlobalSslContextParameters();
    }

    @Test
    void shouldNotOverrideSslContextParametersWhenAlreadySetOnComponentConfiguration() throws Exception {
        KafkaConfiguration base = new KafkaConfiguration();
        SSLContextParameters preset = new SSLContextParameters();
        base.setSslContextParameters(preset);
        component.setConfiguration(base);

        KafkaEndpoint ep = component.createEndpoint(
                "kafka-custom:topicA",
                "topicA",
                new HashMap<>()
        );

        assertSame(preset, ep.getConfiguration().getSslContextParameters());
        verify(component, never()).retrieveGlobalSslContextParameters();
    }

    @Test
    void shouldBindAdditionalPropertiesFromPrefixedParameters() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("additionalProperties.acks", "all");
        params.put("additionalProperties.enable.idempotence", true);

        KafkaEndpoint ep = component.createEndpoint(
                "kafka-custom:topicA",
                "topicA",
                params
        );

        Map<String, Object> additional = ep.getConfiguration().getAdditionalProperties();
        assertEquals("all", additional.get("acks"));
        assertEquals(true, additional.get("enable.idempotence"));
    }

    @Test
    void shouldCopyComponentConfigurationForEndpoint() throws Exception {
        KafkaConfiguration base = new KafkaConfiguration();
        base.setTopic("baseTopic");
        component.setConfiguration(base);

        KafkaEndpoint ep = component.createEndpoint(
                "kafka-custom:topicA",
                "topicA",
                new HashMap<>()
        );

        assertNotSame(base, ep.getConfiguration());
        assertEquals("baseTopic", base.getTopic());
    }
}
