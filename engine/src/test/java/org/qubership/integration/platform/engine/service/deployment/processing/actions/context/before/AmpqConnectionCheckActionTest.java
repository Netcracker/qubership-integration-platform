package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.before;


import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.model.ElementOptions;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.VariablesService;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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

    @Test
    @DisplayName("A producer is checked against the exchange it will publish to")
    void producerIsCheckedAgainstItsExchange() throws IOException {
        Channel channel = mock(Channel.class);

        AmpqConnectionCheckAction.assertTopologyExists(channel, true, "orders-exchange", null);
        verify(channel).exchangeDeclarePassive("orders-exchange");
        verifyNoMoreInteractions(channel);
    }

    @Test
    @DisplayName("A producer publishing through the default exchange has nothing to check")
    void defaultExchangeIsNotCheckedAgainstTheBroker() throws IOException {
        // The broker pre-declares it and refuses to declare it again, even passively. Camel sends
        // through it whenever the name is empty or "default", so the element ships with "default".
        Channel channel = mock(Channel.class);

        AmpqConnectionCheckAction.assertTopologyExists(channel, true, "default", null);
        AmpqConnectionCheckAction.assertTopologyExists(channel, true, "", null);
        AmpqConnectionCheckAction.assertTopologyExists(channel, true, null, null);

        verify(channel, never()).exchangeDeclarePassive(anyString());
    }

    @Test
    @DisplayName("A consumer is checked against every queue it will read from")
    void consumerIsCheckedAgainstEveryQueue() throws IOException {
        Channel channel = mock(Channel.class);

        AmpqConnectionCheckAction.assertTopologyExists(channel, false, "orders-exchange", "one,two");
        verify(channel).queueDeclarePassive("one");
        verify(channel).queueDeclarePassive("two");
        verify(channel, never()).exchangeDeclarePassive(anyString());
    }

    @Test
    @DisplayName("A missing exchange is a retriable failure that names it")
    void missingExchangeIsReported() throws IOException {
        Channel channel = mock(Channel.class);
        when(channel.exchangeDeclarePassive("no-such")).thenThrow(new IOException("404"));

        assertThatThrownBy(() -> AmpqConnectionCheckAction.assertTopologyExists(channel, true, "no-such", null))
                .isInstanceOf(DeploymentRetriableException.class)
                .hasMessage("AMQP exchange no-such not found, check configuration");
    }

    @Test
    @DisplayName("A missing queue is named in quotes, so a stray space is visible")
    void missingQueueIsReportedWithItsExactName() throws IOException {
        Channel channel = mock(Channel.class);
        when(channel.queueDeclarePassive(" two")).thenThrow(new IOException("404"));

        assertThatThrownBy(() -> AmpqConnectionCheckAction.assertTopologyExists(channel, false, "ex", "one, two"))
                .isInstanceOf(DeploymentRetriableException.class)
                .hasMessage("AMQP queue ' two' not found, check configuration");

        // The queue before it was reached, so the check stops at the first one that is missing.
        verify(channel).queueDeclarePassive("one");
    }

    @Test
    @DisplayName("The connection is built from the element's own fields")
    void connectionIsBuiltFromTheElementsFields() throws Exception {
        ConnectionFactory plain = AmpqConnectionCheckAction.connectionFactory(
                "broker:5672", "user", "secret", "/tenant", null);

        assertThat(plain.getHost()).isEqualTo("broker");
        assertThat(plain.getPort()).isEqualTo(5672);
        assertThat(plain.getUsername()).isEqualTo("user");
        assertThat(plain.getPassword()).isEqualTo("secret");
        assertThat(plain.getVirtualHost()).isEqualTo("/tenant");

        // Blank credentials leave the driver's own defaults alone rather than overwriting them.
        ConnectionFactory defaults = AmpqConnectionCheckAction.connectionFactory(
                "broker:5672", "", "", "", "false");
        assertThat(defaults.getUsername()).isEqualTo("guest");
        assertThat(defaults.getVirtualHost()).isEqualTo("/");
    }

    @Test
    @DisplayName("The deprecated v1 trigger is checked like every other element")
    void deprecatedTriggerIsCheckedToo() {
        // It declares its own queue when the route starts, and it still has to name one that the
        // broker carries: a deprecated element gets no exemption.
        ElementProperties properties = element("rabbitmq", "manual");
        properties.getProperties().put(ElementOptions.ADDRESSES, "127.0.0.1:1");
        properties.getProperties().put(ElementOptions.EXCHANGE, "ex");
        properties.getProperties().put(ElementOptions.QUEUES, "q");

        assertThat(action.applicableTo(properties)).isTrue();
        assertThatThrownBy(() -> action.apply(null, properties, null))
                .isInstanceOf(DeploymentRetriableException.class)
                .hasMessage("Connection configuration is invalid or broker is unavailable");
    }

    @Test
    @DisplayName("A configuration missing what the check needs is rejected before any connection")
    void missingMandatoryParametersAreRejected() {
        ElementProperties noAddresses = element("rabbitmq-trigger-2", "manual");
        noAddresses.getProperties().put(ElementOptions.EXCHANGE, "ex");
        noAddresses.getProperties().put(ElementOptions.QUEUES, "q");

        assertThatThrownBy(() -> action.apply(null, noAddresses, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AMQP mandatory parameters are missing, check configuration");

        ElementProperties noQueues = element("rabbitmq-trigger-2", "manual");
        noQueues.getProperties().put(ElementOptions.ADDRESSES, "127.0.0.1:1");
        noQueues.getProperties().put(ElementOptions.EXCHANGE, "ex");

        assertThatThrownBy(() -> action.apply(null, noQueues, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("An address that is not an address is rejected before any connection")
    void malformedAddressesAreRejected() {
        ElementProperties properties = element("rabbitmq-trigger-2", "manual");
        properties.getProperties().put(ElementOptions.ADDRESSES, "http://broker:5672/path");
        properties.getProperties().put(ElementOptions.EXCHANGE, "ex");
        properties.getProperties().put(ElementOptions.QUEUES, "q");

        assertThatThrownBy(() -> action.apply(null, properties, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AMQP addresses has invalid format, check configuration");
    }

    @Test
    @DisplayName("A broker that cannot be reached is a retriable failure")
    void unreachableBrokerIsRetriable() {
        ElementProperties properties = element("rabbitmq-trigger-2", "manual");
        properties.getProperties().put(ElementOptions.ADDRESSES, "127.0.0.1:1");
        properties.getProperties().put(ElementOptions.EXCHANGE, "ex");
        properties.getProperties().put(ElementOptions.QUEUES, "q");

        assertThatThrownBy(() -> action.apply(null, properties, null))
                .isInstanceOf(DeploymentRetriableException.class)
                .hasMessage("Connection configuration is invalid or broker is unavailable");
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
