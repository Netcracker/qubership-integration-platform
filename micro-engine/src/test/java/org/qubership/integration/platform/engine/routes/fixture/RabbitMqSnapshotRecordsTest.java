package org.qubership.integration.platform.engine.routes.fixture;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureResponse;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RabbitMqSnapshotRecordsTest {
    @Test
    void shouldRejectPreparedSendWhenSenderIsInactive() {
        SnapshotFixtureInteraction interaction = interaction(0, Map.of(), Map.of("expectedSendCount", 0));

        RabbitMqSnapshotRecords.assertPreparedCount(interaction, 3, 0, "Inactive sender");

        assertThrows(AssertionError.class,
                () -> RabbitMqSnapshotRecords.assertPreparedCount(interaction, 3, 1, "Inactive sender"));
    }

    @Test
    void shouldRequirePreparedSendForEachRepeatWhenNoMessageIsPublished() {
        SnapshotFixtureInteraction interaction = interaction(0, Map.of(), Map.of());

        RabbitMqSnapshotRecords.assertPreparedCount(interaction, 3, 3, "Unbound routing key");

        assertThrows(AssertionError.class,
                () -> RabbitMqSnapshotRecords.assertPreparedCount(interaction, 3, 0, "Unbound routing key"));
    }

    @Test
    void shouldRejectInvalidSendCountsWhenResponseIsValidated() {
        for (Object count : List.of(-1, 0.5, "0")) {
            SnapshotFixtureInteraction interaction = interaction(0, Map.of(), Map.of("expectedSendCount", count));

            assertThrows(IllegalArgumentException.class,
                    () -> RabbitMqSnapshotRecords.responseProperties(interaction.getResponse()));
        }
    }

    @Test
    void shouldCheckPreparedHeadersWhenNoMessageIsPublished() {
        SnapshotFixtureInteraction interaction = interaction(0, Collections.singletonMap("Authorization", null), Map.of());

        RabbitMqSnapshotRecords.assertPreparedHeaders(interaction, Map.of());

        assertThrows(AssertionError.class, () -> RabbitMqSnapshotRecords.assertPreparedHeaders(
                interaction, Map.of("Authorization", "Bearer leaked")));
    }

    @Test
    void shouldMatchEachPublishedRequestIdToItsInitializedContextWhenIdsAreGenerated() {
        SnapshotFixtureInteraction interaction = interaction(2, Map.of(),
                Map.of("expectedContextHeaders", Map.of("X-Request-Id", "X-Request-Id")));
        List<RabbitMqSnapshotRecords.ExpectedRecord> expected = RabbitMqSnapshotRecords.expectedRecords(interaction, "orders",
                List.of(Map.of("x-request-id", "first"), Map.of("x-request-id", "second")));

        RabbitMqSnapshotRecords.assertRecords(expected, List.of(record("first"), record("second")), "Generated request IDs");

        assertThrows(AssertionError.class, () -> RabbitMqSnapshotRecords.assertRecords(
                expected, List.of(record("first"), record("first")), "Generated request IDs"));
    }

    @Test
    void shouldRejectMissingContextWhenPropagationIsExpected() {
        SnapshotFixtureInteraction interaction = interaction(1, Map.of(),
                Map.of("expectedContextHeaders", Map.of("X-Request-Id", "X-Request-Id")));

        assertThrows(AssertionError.class,
                () -> RabbitMqSnapshotRecords.expectedRecords(interaction, "orders", List.of(Map.of())));
    }

    @Test
    void shouldCheckPreparedHeadersWhenMessageIsPublished() {
        SnapshotFixtureInteraction interaction = interaction(1, Map.of(),
                Map.of("expectedPreparedHeaders", Map.of("X-Test-Param", "overridden")));

        RabbitMqSnapshotRecords.assertPreparedHeaders(interaction, Map.of("X-Test-Param", "overridden"));

        assertThrows(AssertionError.class,
                () -> RabbitMqSnapshotRecords.assertPreparedHeaders(interaction, Map.of("X-Test-Param", "incoming")));
    }

    private static SnapshotFixtureInteraction interaction(int count, Map<String, Object> headers, Map<String, Object> properties) {
        SnapshotFixtureRequestExpectation request = new SnapshotFixtureRequestExpectation(count, null, null, null,
                "test", null, null, headers, Map.of());
        return new SnapshotFixtureInteraction("rabbitmq", new SnapshotFixtureResponse(null, null, null, null, properties),
                request, null, null, null, null, null);
    }

    private static GetResponse record(String requestId) {
        return new GetResponse(new Envelope(1, false, "test", "orders"),
                new AMQP.BasicProperties.Builder().headers(Map.of("X-Request-Id", requestId)).build(), new byte[0], 0);
    }
}
