package org.qubership.integration.platform.engine.routes.fixture;

import com.netcracker.cloud.maas.client.api.Classifier;
import com.netcracker.cloud.maas.client.api.rabbit.RabbitMaaSClient;
import com.netcracker.cloud.maas.client.api.rabbit.VHost;
import jakarta.enterprise.inject.spi.CDI;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureResponse;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

final class RabbitMqSnapshotMaas {
    static final String USERNAME = "maas-sender";
    static final String PASSWORD = "maas-password";
    static final String VHOST = "snapshot-maas";

    private final Set<Classifier> expectedClassifiers;
    private RabbitMaaSClient client;
    private String expectedAddresses;

    RabbitMqSnapshotMaas(List<SnapshotFixtureBinding> bindings) {
        expectedClassifiers = maasProperties(bindings)
                .map(properties -> new Classifier((String) properties.get("maasClassifier"),
                        Classifier.NAMESPACE, (String) properties.get("maasNamespace")))
                .collect(Collectors.toSet());
    }

    static boolean usesMaas(List<SnapshotFixtureBinding> bindings) {
        return maasProperties(bindings).findAny().isPresent();
    }

    void beforeRouteLoad(String host, int port) throws URISyntaxException {
        expectedAddresses = host + ':' + port;
        VHost virtualHost = new VHost();
        virtualHost.setCnn(new URI("amqp", null, host, port, '/' + VHOST, null, null).toString());
        virtualHost.setUsername(USERNAME);
        virtualHost.setEncodedPassword("plain:" + PASSWORD);
        client = CDI.current().select(RabbitMaaSClient.class).get();
        for (Classifier classifier : expectedClassifiers) {
            when(client.getVirtualHost(classifier)).thenReturn(virtualHost);
        }
    }

    void verifyResolvedEndpoint(Map<String, String> parameters) {
        assertEquals(expectedAddresses, parameters.get("addresses"), "RabbitMQ did not resolve the MaaS broker address.");
        assertEquals(USERNAME, parameters.get("username"), "RabbitMQ did not resolve the MaaS username.");
        assertEquals(PASSWORD, parameters.get("password"), "RabbitMQ did not resolve the MaaS password.");
        assertEquals(VHOST, parameters.get("vhost"), "RabbitMQ did not resolve the MaaS virtual host.");
    }

    void verifyLookups() {
        for (Classifier classifier : expectedClassifiers) {
            verify(client, atLeastOnce()).getVirtualHost(classifier);
        }
        verifyNoMoreInteractions(client);
    }

    private static Stream<Map<String, Object>> maasProperties(List<SnapshotFixtureBinding> bindings) {
        return bindings.stream()
                .flatMap(binding -> binding.interactionsByInvocationId().values().stream())
                .flatMap(interaction -> Stream.ofNullable(interaction.getResponse()))
                .map(SnapshotFixtureResponse::getProperties)
                .filter(properties -> properties.containsKey("maasClassifier"));
    }
}
