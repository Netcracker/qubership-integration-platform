package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.BytesMessage;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.MapMessage;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageEOFException;
import jakarta.jms.ObjectMessage;
import jakarta.jms.Queue;
import jakarta.jms.StreamMessage;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureResponse;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

final class JmsSnapshotRecords {
    private static final ObjectMapper OBJECT_MAPPER = ObjectMappers.getObjectMapper();
    private static final Duration RECORD_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration EXTRA_RECORD_TIMEOUT = Duration.ofMillis(250);

    private JmsSnapshotRecords() {
    }

    static Map<String, Object> responseProperties(SnapshotFixtureResponse response) {
        if (response == null) {
            return Map.of();
        }
        if (response.hasExplicitStatus() || response.getDelayMillis() != null || response.getBody() != null
                || !response.getHeaders().isEmpty()) {
            throw new IllegalArgumentException("Artemis JMS fixture responses support only properties.");
        }
        Map<String, Object> properties = response.getProperties();
        if (properties.containsKey("expectedSendCount")
                && (!(properties.get("expectedSendCount") instanceof Integer count) || count < 0)) {
            throw new IllegalArgumentException("Artemis JMS expectedSendCount must be a nonnegative integer.");
        }
        if (properties.containsKey("subscriberCount")
                && (!(properties.get("subscriberCount") instanceof Integer count) || count < 1)) {
            throw new IllegalArgumentException("Artemis JMS subscriberCount must be a positive integer.");
        }
        return properties;
    }

    static int expectedSendCount(SnapshotFixtureInteraction interaction) {
        return (Integer) responseProperties(interaction.getResponse())
                .getOrDefault("expectedSendCount", interaction.getExpectedRequest().getCount());
    }

    static List<Record> read(MessageConsumer consumer, int expectedCount, boolean senderEntered, String description) {
        List<Record> records = new ArrayList<>();
        long deadline = System.nanoTime() + RECORD_TIMEOUT.toNanos();
        try {
            while (records.size() < expectedCount && System.nanoTime() < deadline) {
                long remainingMillis = Math.max(1, Duration.ofNanos(deadline - System.nanoTime()).toMillis());
                Message message = consumer.receive(remainingMillis);
                if (message == null) {
                    break;
                }
                records.add(observe(message));
            }
            Message extra = senderEntered ? consumer.receive(EXTRA_RECORD_TIMEOUT.toMillis()) : consumer.receiveNoWait();
            while (extra != null) {
                records.add(observe(extra));
                extra = consumer.receiveNoWait();
            }
            return List.copyOf(records);
        } catch (JMSException exception) {
            throw new IllegalStateException("Cannot read messages for " + description + ".", exception);
        }
    }

    static void assertRecords(SnapshotFixtureInteraction interaction, List<Record> records, String description) {
        SnapshotFixtureRequestExpectation expected = interaction.getExpectedRequest();
        Map<String, Object> properties = responseProperties(interaction.getResponse());
        assertEquals(expected.getCount(), records.size(), description + " received an unexpected number of messages.");
        for (Record record : records) {
            assertEquals(expected.getDestination(), record.destinationName(), description + " destination");
            assertEquals(properties.getOrDefault("expectedMessageType", "Bytes"), record.messageType(), description + " message type");
            assertEquals(properties.getOrDefault("expectedDestinationType", "queue"), record.destinationType(), description + " destination type");
            if (expected.hasBody()) {
                Object expectedBody = record.bytes() == null ? expected.getBody() : textBody(expected.getBody());
                assertEquals(expectedBody, record.body(), description + " body");
            }
            if (properties.containsKey("expectedBodyHex")) {
                assertArrayEquals(HexFormat.of().parseHex((String) properties.get("expectedBodyHex")), record.bytes(),
                        description + " body bytes");
            }
            SnapshotValueAssertions.assertMapValues(expected.getHeaders(), record.properties(), description + " header");
            SnapshotValueAssertions.assertMapValues(headerMap(properties, "expectedJmsHeaders"), record.headers(),
                    description + " JMS header");
        }
    }

    private static Map<String, Object> headerMap(Map<String, Object> properties, String name) {
        Object value = properties.get(name);
        if (value == null) {
            return Map.of();
        }
        Map<?, ?> headers = assertInstanceOf(Map.class, value, "Artemis JMS " + name + " must be a map.");
        Map<String, Object> result = new LinkedHashMap<>();
        headers.forEach((key, header) -> result.put(String.valueOf(key), header));
        return result;
    }

    static Record observe(Message message) throws JMSException {
        String type;
        Object body;
        byte[] bytes = null;
        switch (message) {
            case BytesMessage bytesMessage -> {
                type = "Bytes";
                bytes = bytesMessage.getBodyLength() == 0 ? new byte[0] : bytesMessage.getBody(byte[].class);
                body = new String(bytes, StandardCharsets.UTF_8);
            }
            case TextMessage textMessage -> {
                type = "Text";
                body = textMessage.getText();
            }
            case MapMessage mapMessage -> {
                type = "Map";
                Map<String, Object> values = new LinkedHashMap<>();
                Enumeration<?> names = mapMessage.getMapNames();
                while (names.hasMoreElements()) {
                    String name = (String) names.nextElement();
                    values.put(name, mapMessage.getObject(name));
                }
                body = values;
            }
            case ObjectMessage objectMessage -> {
                type = "Object";
                body = objectMessage.getObject();
            }
            case StreamMessage streamMessage -> {
                type = "Stream";
                bytes = streamBytes(streamMessage);
                body = new String(bytes, StandardCharsets.UTF_8);
            }
            default -> throw new IllegalArgumentException("Unsupported JMS message type: " + message.getClass().getName());
        }
        Destination destination = message.getJMSDestination();
        String destinationType = destination instanceof Topic ? "topic" : "queue";
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("JMSCorrelationID", message.getJMSCorrelationID());
        headers.put("JMSType", message.getJMSType());
        headers.put("JMSReplyTo", destinationName(message.getJMSReplyTo()));
        headers.put("JMSMessageID", message.getJMSMessageID());
        headers.put("JMSRedelivered", message.getJMSRedelivered());
        headers.put("JMSPriority", message.getJMSPriority());
        headers.put("JMSDeliveryMode", message.getJMSDeliveryMode());
        return new Record(destinationName(destination), destinationType, type, body, bytes,
                immutableJmsProperties(message), Collections.unmodifiableMap(headers));
    }

    private static byte[] streamBytes(StreamMessage message) throws JMSException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            while (true) {
                output.writeBytes(assertInstanceOf(byte[].class, message.readObject(),
                        "Generated JMS StreamMessage must contain byte chunks."));
            }
        } catch (MessageEOFException end) {
            return output.toByteArray();
        }
    }

    private static String destinationName(Destination destination) throws JMSException {
        return switch (destination) {
            case null -> null;
            case Topic topic -> topic.getTopicName();
            case Queue queue -> queue.getQueueName();
            default -> throw new IllegalArgumentException("Unsupported JMS destination: " + destination);
        };
    }

    private static Map<String, Object> immutableJmsProperties(Message message) throws JMSException {
        Map<String, Object> properties = new TreeMap<>();
        Enumeration<?> names = message.getPropertyNames();
        while (names.hasMoreElements()) {
            String name = (String) names.nextElement();
            properties.put(name, message.getObjectProperty(name));
        }
        return Collections.unmodifiableMap(properties);
    }

    private static String textBody(Object body) {
        if (body == null) {
            return "";
        }
        if (body instanceof String text) {
            return text;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize the expected Artemis JMS message body.", exception);
        }
    }

    record Record(String destinationName, String destinationType, String messageType, Object body, byte[] bytes,
                  Map<String, Object> properties, Map<String, Object> headers) {
    }
}
