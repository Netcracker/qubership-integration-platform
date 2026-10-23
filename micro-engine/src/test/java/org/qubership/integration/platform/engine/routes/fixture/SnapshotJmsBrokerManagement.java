package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.util.List;
import java.util.Map;

final class SnapshotJmsBrokerManagement implements AutoCloseable {
    private static final ObjectMapper MAPPER = ObjectMappers.getObjectMapper();
    private final Connection connection;
    private final Session session;
    private final TemporaryQueue replies;
    private final MessageProducer producer;
    private final MessageConsumer consumer;

    SnapshotJmsBrokerManagement(Connection connection) throws Exception {
        this.connection = connection;
        session = connection.createSession(Session.AUTO_ACKNOWLEDGE);
        replies = session.createTemporaryQueue();
        producer = session.createProducer(session.createQueue("activemq.management"));
        consumer = session.createConsumer(replies);
        connection.start();
    }

    void setMaxDeliveryAttempts(String destination, int attempts) throws Exception {
        String settings = MAPPER.writeValueAsString(Map.of(
                "maxDeliveryAttempts", attempts, "deadLetterAddress", "DLQ", "redeliveryDelay", 0));
        invoke("addAddressSettings", List.of(destination, settings));
    }

    void addUser(String username, String password, String role) throws Exception {
        invoke("addUser", List.of(username, password, role, false));
    }

    int topicConsumerCount(String destination) throws Exception {
        String filter = MAPPER.writeValueAsString(Map.of("field", "address", "operation", "EQUALS", "value", destination));
        JsonNode result = invoke("listConsumers", List.of(filter, -1, -1));
        return MAPPER.readTree(result.textValue()).required("data").size();
    }

    private JsonNode invoke(String operation, List<?> arguments) throws Exception {
        Message request = session.createTextMessage(MAPPER.writeValueAsString(arguments));
        request.setStringProperty("_AMQ_ResourceName", "broker");
        request.setStringProperty("_AMQ_OperationName", operation);
        request.setJMSReplyTo(replies);
        producer.send(request);
        Message response = consumer.receive(5000);
        if (response == null) {
            throw new IllegalStateException("Artemis management operation '" + operation + "' did not reply within 5000 ms.");
        }
        String body = response.getBody(String.class);
        JsonNode result = body == null ? null : MAPPER.readTree(body);
        if (!response.getBooleanProperty("_AMQ_OperationSucceeded")) {
            throw new IllegalStateException("Artemis management operation '" + operation + "' failed: " + result);
        }
        return result == null ? null : result.get(0);
    }

    @Override
    public void close() throws Exception {
        connection.close();
    }
}
