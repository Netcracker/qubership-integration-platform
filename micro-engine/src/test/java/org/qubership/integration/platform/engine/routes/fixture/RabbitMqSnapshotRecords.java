package org.qubership.integration.platform.engine.routes.fixture;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.LongString;
import org.apache.commons.collections4.map.CaseInsensitiveMap;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureResponse;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class RabbitMqSnapshotRecords {
    private static final Duration RECORD_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration QUIET_PERIOD = Duration.ofMillis(200);
    private static final Set<String> RESPONSE_PROPERTIES = Set.of("bindings", "expectedMessageProperties",
            "expectedBodyBase64", "expectedBodyLength", "expectedBodySha256", "expectedContextHeaders", "expectedPreparedHeaders",
            "brokerAction", "expectedChannelCloseCode", "maasClassifier", "maasNamespace", "expectedSendCount");

    private RabbitMqSnapshotRecords() {
    }

    static Map<String, Object> responseProperties(SnapshotFixtureResponse response) {
        if (response == null) {
            return Map.of();
        }
        if (response.hasExplicitStatus() || response.getDelayMillis() != null || response.getBody() != null
                || !response.getHeaders().isEmpty()) {
            throw new IllegalArgumentException("RabbitMQ fixture responses support only properties.");
        }
        if (!RESPONSE_PROPERTIES.containsAll(response.getProperties().keySet())) {
            throw new IllegalArgumentException("RabbitMQ fixture response contains an unsupported property.");
        }
        if (response.getProperties().containsKey("expectedSendCount")
                && (!(response.getProperties().get("expectedSendCount") instanceof Integer count) || count < 0)) {
            throw new IllegalArgumentException("RabbitMQ expectedSendCount must be a nonnegative integer.");
        }
        return response.getProperties();
    }

    static int expectedSendCount(SnapshotFixtureInteraction interaction, int repeat) {
        return interaction == null ? 0
                : (Integer) responseProperties(interaction.getResponse()).getOrDefault("expectedSendCount", repeat);
    }

    static void assertPreparedCount(SnapshotFixtureInteraction interaction, int repeat, int actualCount, String description) {
        assertEquals(expectedSendCount(interaction, repeat), actualCount,
                description + " prepared an unexpected number of logical sends.");
    }

    static List<ExpectedRecord> expectedRecords(SnapshotFixtureInteraction interaction, String routingKey,
                                                List<Map<String, Object>> initializedContexts) {
        SnapshotFixtureRequestExpectation expectation = interaction.getExpectedRequest();
        Map<String, Object> properties = responseProperties(interaction.getResponse());
        Map<String, Object> contextHeaders = headerMap(properties, "expectedContextHeaders");
        if (!contextHeaders.isEmpty()) {
            assertEquals(initializedContexts.size(), expectation.getCount(),
                    "RabbitMQ context-header expectations require one record per invocation.");
        }
        List<ExpectedRecord> records = new ArrayList<>();
        for (int index = 0; index < expectation.getCount(); index++) {
            Map<String, Object> resolvedHeaders = new LinkedHashMap<>();
            if (!contextHeaders.isEmpty()) {
                Map<String, Object> context = new CaseInsensitiveMap<>(initializedContexts.get(index));
                contextHeaders.forEach((header, contextHeader) -> {
                    Object value = context.get(String.valueOf(contextHeader));
                    assertNotNull(value,
                            "RabbitMQ expected context header '" + contextHeader + "' was not initialized.");
                    resolvedHeaders.put(header, value);
                });
            }
            records.add(new ExpectedRecord(expectation, expectation.getKey() == null ? routingKey : expectation.getKey(),
                    properties, resolvedHeaders));
        }
        return records;
    }

    static Map<String, Object> headerMap(Map<String, Object> properties, String name) {
        Object value = properties.get(name);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> headers)) {
            throw new IllegalArgumentException("RabbitMQ " + name + " must be a map.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        headers.forEach((key, headerValue) -> result.put(String.valueOf(key), headerValue));
        return result;
    }

    static void assertPreparedHeaders(SnapshotFixtureInteraction interaction, Map<String, Object> headers) {
        if (interaction.getExpectedRequest().getCount() == 0) {
            SnapshotValueAssertions.assertMapValues(interaction.getExpectedRequest().getHeaders(), headers,
                    "RabbitMQ prepared an unexpected header for a send with no published record");
        }
        SnapshotValueAssertions.assertMapValues(headerMap(responseProperties(interaction.getResponse()), "expectedPreparedHeaders"),
                headers, "RabbitMQ prepared an unexpected header");
    }

    static List<GetResponse> read(Connection connection, String queue, int expectedCount) {
        List<GetResponse> records = new ArrayList<>();
        long deadline = System.nanoTime() + RECORD_TIMEOUT.toNanos();
        long quietDeadline = System.nanoTime() + QUIET_PERIOD.toNanos();
        try (Channel channel = connection.createChannel()) {
            while (System.nanoTime() < deadline) {
                GetResponse response = channel.basicGet(queue, true);
                if (response != null) {
                    records.add(response);
                    quietDeadline = System.nanoTime() + QUIET_PERIOD.toNanos();
                } else if (records.size() >= expectedCount && System.nanoTime() >= quietDeadline) {
                    break;
                } else {
                    Thread.sleep(25);
                }
            }
            return List.copyOf(records);
        } catch (IOException | TimeoutException exception) {
            throw new IllegalStateException("Cannot read records from RabbitMQ queue '" + queue + "'.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for RabbitMQ records.", exception);
        }
    }

    static void assertRecords(List<ExpectedRecord> expected, List<GetResponse> actual, String description) {
        assertEquals(expected.size(), actual.size(), description + " received an unexpected number of records.");
        for (int index = 0; index < expected.size(); index++) {
            ExpectedRecord record = expected.get(index);
            GetResponse response = actual.get(index);
            String label = description + " record " + (index + 1);
            assertEquals(record.expectation().getDestination(), response.getEnvelope().getExchange(), label + " exchange");
            assertEquals(record.routingKey(), response.getEnvelope().getRoutingKey(), label + " routing key");
            assertBody(record, response.getBody(), label);
            SnapshotValueAssertions.assertMapValues(record.expectation().getHeaders(), normalizeHeaders(response.getProps().getHeaders()),
                    label + " has an unexpected header");
            SnapshotValueAssertions.assertMapValues(record.contextHeaders(), normalizeHeaders(response.getProps().getHeaders()),
                    label + " has an unexpected propagated context header");
            Object configuredProperties = record.properties().get("expectedMessageProperties");
            if (configuredProperties != null) {
                if (!(configuredProperties instanceof Map<?, ?> properties)) {
                    throw new IllegalArgumentException("RabbitMQ expectedMessageProperties must be a map.");
                }
                properties.forEach((key, value) -> assertEquals(value, messageProperty(response.getProps(), String.valueOf(key)),
                        label + " has an unexpected AMQP property '" + key + "'."));
            }
        }
    }

    private static void assertBody(ExpectedRecord record, byte[] body, String label) {
        Map<String, Object> properties = record.properties();
        if (properties.containsKey("expectedBodyBase64")) {
            assertArrayEquals(Base64.getDecoder().decode((String) properties.get("expectedBodyBase64")), body, label + " body bytes");
        } else if (record.expectation().hasBody()) {
            Object expected = record.expectation().getBody();
            assertEquals(expected == null ? "" : String.valueOf(expected), new String(body, StandardCharsets.UTF_8), label + " body");
        }
        if (properties.containsKey("expectedBodyLength")) {
            assertEquals(((Number) properties.get("expectedBodyLength")).intValue(), body.length, label + " body length");
        }
        if (properties.containsKey("expectedBodySha256")) {
            assertEquals(properties.get("expectedBodySha256"), sha256(body), label + " body SHA-256");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static Object messageProperty(AMQP.BasicProperties properties, String name) {
        return switch (name) {
            case "contentType" -> properties.getContentType();
            case "contentEncoding" -> properties.getContentEncoding();
            case "deliveryMode" -> properties.getDeliveryMode();
            case "priority" -> properties.getPriority();
            case "correlationId" -> properties.getCorrelationId();
            case "replyTo" -> properties.getReplyTo();
            case "expiration" -> properties.getExpiration();
            case "messageId" -> properties.getMessageId();
            case "timestamp" -> properties.getTimestamp() == null ? null : properties.getTimestamp().getTime();
            case "type" -> properties.getType();
            case "userId" -> properties.getUserId();
            case "appId" -> properties.getAppId();
            case "clusterId" -> properties.getClusterId();
            default -> throw new IllegalArgumentException("Unsupported RabbitMQ message property '" + name + "'.");
        };
    }

    private static Map<String, Object> normalizeHeaders(Map<String, Object> headers) {
        if (headers == null) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        headers.forEach((name, value) -> normalized.put(name, normalizeValue(value)));
        return normalized;
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof LongString string) {
            return string.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((name, item) -> normalized.put(String.valueOf(name), normalizeValue(item)));
            return normalized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(RabbitMqSnapshotRecords::normalizeValue).toList();
        }
        return value;
    }

    record ExpectedRecord(SnapshotFixtureRequestExpectation expectation, String routingKey, Map<String, Object> properties,
                          Map<String, Object> contextHeaders) {
    }
}
