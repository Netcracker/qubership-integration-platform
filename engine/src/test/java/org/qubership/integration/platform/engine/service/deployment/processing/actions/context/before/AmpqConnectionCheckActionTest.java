package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.before;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.ElementOptions;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.VariablesService;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AmpqConnectionCheckActionTest {

    private AmpqConnectionCheckAction action;

    @BeforeEach
    void setUp() {
        VariablesService variablesService = mock(VariablesService.class);
        when(variablesService.injectVariables(anyString())).thenAnswer(i -> i.getArgument(0));
        when(variablesService.injectVariables(null)).thenReturn(null);
        action = new AmpqConnectionCheckAction(variablesService);
    }

    @Test
    @DisplayName("A connection the user typed in is checked, not only one MaaS supplied")
    void manualConnectionIsChecked() {
        assertThat(action.applicableTo(element("rabbitmq-trigger-2", "manual"))).isTrue();
        assertThat(action.applicableTo(element("rabbitmq-sender-2", "manual"))).isTrue();
    }

    @Test
    @DisplayName("A MaaS connection is still checked")
    void maasConnectionIsStillChecked() {
        assertThat(action.applicableTo(element("rabbitmq-trigger-2", "maas"))).isTrue();
        assertThat(action.applicableTo(element("rabbitmq-sender-2", "maas_by_classifier"))).isTrue();
    }

    @Test
    @DisplayName("An element that names no connection source at all is checked")
    void absentConnectionSourceIsChecked() {
        ElementProperties properties = element("rabbitmq-trigger-2", null);
        properties.getProperties().remove(ElementOptions.CONNECTION_SOURCE_TYPE_PROP);

        assertThat(action.applicableTo(properties)).isTrue();
    }

    @Test
    @DisplayName("A generic element speaking another protocol is left alone")
    void otherProtocolsAreLeftAlone() {
        ElementProperties asyncApiOverHttp = element("async-api-trigger", "manual");
        asyncApiOverHttp.getProperties().put(ChainProperties.OPERATION_PROTOCOL_TYPE_PROP, "http");
        assertThat(action.applicableTo(asyncApiOverHttp)).isFalse();

        ElementProperties serviceCallOverAmqp = element("service-call", "manual");
        serviceCallOverAmqp.getProperties().put(ChainProperties.OPERATION_PROTOCOL_TYPE_PROP, "amqp");
        assertThat(action.applicableTo(serviceCallOverAmqp)).isTrue();
    }

    @Test
    @DisplayName("An element of another kind is left alone")
    void otherElementsAreLeftAlone() {
        assertThat(action.applicableTo(element("kafka-trigger-2", "manual"))).isFalse();
        assertThat(action.applicableTo(element("http-trigger", "manual"))).isFalse();
    }

    @Test
    @DisplayName("An element that declares its own topology is not asked to have one already")
    void declaringElementsAreNotAskedForExistingTopology() {
        // The deprecated v1 trigger leaves Camel's autoDeclare at its default, which is on for a
        // consumer: it creates the queue itself, so demanding one beforehand would block a chain
        // that works.
        assertThat(AmpqConnectionCheckAction.declaresItsOwnTopology(
                ChainElementType.RABBITMQ_TRIGGER, Map.of())).isTrue();

        assertThat(AmpqConnectionCheckAction.declaresItsOwnTopology(
                ChainElementType.RABBITMQ_TRIGGER_2, Map.of(ElementOptions.AUTO_DECLARE, "true"))).isTrue();
        assertThat(AmpqConnectionCheckAction.declaresItsOwnTopology(
                ChainElementType.RABBITMQ_TRIGGER_2, Map.of(ElementOptions.AUTO_DECLARE, "false"))).isFalse();
        assertThat(AmpqConnectionCheckAction.declaresItsOwnTopology(
                ChainElementType.RABBITMQ_TRIGGER_2, Map.of())).isFalse();
    }

    @Test
    @DisplayName("A producer never declares, whatever it is")
    void producersNeverDeclare() {
        // Camel's autoDeclareProducer is off by default and no template turns it on.
        assertThat(AmpqConnectionCheckAction.declaresItsOwnTopology(
                ChainElementType.RABBITMQ_SENDER, Map.of(ElementOptions.AUTO_DECLARE, "true"))).isFalse();
        assertThat(AmpqConnectionCheckAction.declaresItsOwnTopology(
                ChainElementType.RABBITMQ_SENDER_2, Map.of())).isFalse();
        assertThat(AmpqConnectionCheckAction.declaresItsOwnTopology(
                ChainElementType.ASYNCAPI_TRIGGER, Map.of())).isFalse();
    }

    @Test
    @DisplayName("The queue list is taken apart, because a passive declare names one queue")
    void queueListIsTakenApart() {
        assertThat(AmpqConnectionCheckAction.queueNames("one")).containsExactly("one");
        assertThat(AmpqConnectionCheckAction.queueNames("one,two")).containsExactly("one", "two");
    }

    @Test
    @DisplayName("Whitespace is kept, because the consumer keeps it too")
    void whitespaceIsKept() {
        // Camel consumes from getQueues().split(",") verbatim, so "one, two" really does ask the
        // broker for a queue named " two". Trimming here would pass a chain that cannot consume.
        assertThat(AmpqConnectionCheckAction.queueNames("one, two")).containsExactly("one", " two");
        assertThat(AmpqConnectionCheckAction.queueNames(" one ")).containsExactly(" one ");
    }

    @Test
    @DisplayName("A list that names nothing yields nothing to check")
    void emptyQueueListYieldsNothing() {
        assertThat(AmpqConnectionCheckAction.queueNames(null)).isEmpty();
    }

    private static ElementProperties element(String type, String connectionSourceType) {
        Map<String, String> properties = new HashMap<>();
        properties.put(ChainProperties.ELEMENT_TYPE, type);
        if (connectionSourceType != null) {
            properties.put(ElementOptions.CONNECTION_SOURCE_TYPE_PROP, connectionSourceType);
        }
        return ElementProperties.builder().elementId("element-id").properties(properties).build();
    }
}
