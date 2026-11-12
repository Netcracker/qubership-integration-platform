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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    long applicationMessageCount() throws Exception {
        JsonNode result = invoke("listQueues", List.of("", -1, -1));
        JsonNode queues = MAPPER.readTree(result.textValue()).required("data");
        long count = 0;
        for (JsonNode queue : queues) {
            if (queue.path("name").asText().startsWith("jms/snapshot/")) {
                count += queue.path("messageCount").asLong();
            }
        }
        return count;
    }

    Set<String> queueNames() throws Exception {
        return names("queueNames");
    }

    Set<String> addressNames() throws Exception {
        return names("addressNames");
    }

    String replyQueueName() throws Exception {
        return replies.getQueueName();
    }

    int queueConsumerCount(String queue) throws Exception {
        return Math.toIntExact(queueCount(queue, "consumerCount"));
    }

    long queueMessageCount(String queue) throws Exception {
        return queueCount(queue, "messageCount");
    }

    private long queueCount(String queue, String attribute) throws Exception {
        long count = attribute("queue." + queue, attribute).asLong(-1);
        if (count < 0) {
            throw new IllegalStateException("Artemis queue '" + queue + "' returned an invalid " + attribute + ".");
        }
        return count;
    }

    void purgeQueue(String queue) throws Exception {
        invoke("queue." + queue, "removeMessages", List.of(""));
    }

    void destroyQueue(String queue) throws Exception {
        invoke("destroyQueue", List.of(queue, false, false));
    }

    void deleteAddress(String address) throws Exception {
        invoke("deleteAddress", List.of(address));
    }

    void removeAddressSettings(String address) throws Exception {
        invoke("removeAddressSettings", List.of(address));
    }

    private Set<String> names(String attribute) throws Exception {
        JsonNode values = attribute("broker", attribute);
        if (!values.isArray()) {
            throw new IllegalStateException("Artemis broker attribute '" + attribute + "' must contain an array.");
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode name : values) {
            if (!name.isTextual() || name.textValue().isBlank()) {
                throw new IllegalStateException("Artemis broker attribute '" + attribute + "' contains an invalid resource name.");
            }
            names.add(name.textValue());
        }
        names.remove(replyQueueName());
        return names;
    }

    boolean persistsDeliveryCountBeforeDelivery() throws Exception {
        return attribute("broker", "persistDeliveryCountBeforeDelivery").booleanValue();
    }

    private JsonNode attribute(String resource, String attribute) throws Exception {
        Message request = session.createMessage();
        request.setStringProperty("_AMQ_ResourceName", resource);
        request.setStringProperty("_AMQ_Attribute", attribute);
        return exchange(request, "attribute '" + attribute + "' on '" + resource + "'", false);
    }

    private JsonNode invoke(String operation, List<?> arguments) throws Exception {
        return invoke("broker", operation, arguments);
    }

    private JsonNode invoke(String resource, String operation, List<?> arguments) throws Exception {
        Message request = session.createTextMessage(MAPPER.writeValueAsString(arguments));
        request.setStringProperty("_AMQ_ResourceName", resource);
        request.setStringProperty("_AMQ_OperationName", operation);
        return exchange(request, "operation '" + operation + "' on '" + resource + "'", true);
    }

    private JsonNode exchange(Message request, String description, boolean operation) throws Exception {
        request.setJMSReplyTo(replies);
        producer.send(request);
        Message response = consumer.receive(5000);
        if (response == null) {
            throw new IllegalStateException("Artemis management " + description + " did not reply within 5000 ms.");
        }
        String body = response.getBody(String.class);
        JsonNode result = body == null ? null : MAPPER.readTree(body);
        if ((operation || response.propertyExists("_AMQ_OperationSucceeded")) && !response.getBooleanProperty("_AMQ_OperationSucceeded")) {
            throw new IllegalStateException("Artemis management " + description + " failed: " + result);
        }
        return result == null ? null : result.get(0);
    }

    @Override
    public void close() throws Exception {
        connection.close();
    }
}
