package org.qubership.integration.platform.engine.camel.components.kafka.consumer;

import org.apache.camel.Message;
import org.apache.camel.component.kafka.KafkaConstants;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaLegacyHeaderCompatibility;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaLegacyHeaderCompatibilityTest {

    @Test
    void shouldCopyEachConfiguredHeaderOnce() {
        Message message = mock(Message.class);
        when(message.getHeader(KafkaConstants.KEY)).thenReturn("record-key");
        when(message.getHeader(KafkaConstants.TOPIC)).thenReturn("record-topic");
        when(message.getHeader(KafkaConstants.HEADERS)).thenReturn("record-headers");
        when(message.getHeader(KafkaConstants.PARTITION_KEY)).thenReturn("partition-key");
        when(message.getHeader(KafkaConstants.PARTITION)).thenReturn("partition");
        when(message.getHeader(KafkaConstants.OVERRIDE_TOPIC)).thenReturn("override-topic");
        when(message.getHeader(KafkaConstants.OFFSET)).thenReturn("offset");

        new KafkaLegacyHeaderCompatibility(List.of(
            "kafka.KEY", "kafka.TOPIC", "kafka.HEADERS", "kafka.PARTITION_KEY", "kafka.PARTITION",
            "kafka.OVERRIDE_TOPIC", "kafka.OFFSET"))
            .apply(message);

        verify(message, times(1)).getHeader(KafkaConstants.KEY);
        verify(message, times(1)).getHeader(KafkaConstants.TOPIC);
        verify(message, times(1)).getHeader(KafkaConstants.HEADERS);
        verify(message).setHeader("kafka.KEY", "record-key");
        verify(message).setHeader("kafka.TOPIC", "record-topic");
        verify(message).setHeader("kafka.HEADERS", "record-headers");
        verify(message).setHeader("kafka.PARTITION_KEY", "partition-key");
        verify(message).setHeader("kafka.PARTITION", "partition");
        verify(message).setHeader("kafka.OVERRIDE_TOPIC", "override-topic");
        verify(message).setHeader("kafka.OFFSET", "offset");
    }

    @Test
    void shouldCopyConfiguredLegacyValuesOverCurrentHeadersBeforeSending() {
        Message message = mock(Message.class);
        when(message.getHeader("kafka.KEY")).thenReturn("legacy-key");
        when(message.getHeader(KafkaConstants.KEY)).thenReturn("current-key");
        when(message.getHeader("kafka.OVERRIDE_TOPIC")).thenReturn("legacy-topic");
        when(message.getHeader(KafkaConstants.OVERRIDE_TOPIC)).thenReturn("current-topic");

        new KafkaLegacyHeaderCompatibility(List.of("kafka.KEY", "kafka.OVERRIDE_TOPIC"))
            .applyLegacyOverrides(message);

        verify(message).setHeader(KafkaConstants.KEY, "legacy-key");
        verify(message).setHeader(KafkaConstants.OVERRIDE_TOPIC, "legacy-topic");
    }

    @Test
    void shouldLeaveCurrentHeadersUntouchedWhenLegacyAliasesAreMissingOrUnconfigured() {
        Message message = mock(Message.class);
        when(message.getHeader("kafka.KEY")).thenReturn(null);
        when(message.getHeader(KafkaConstants.KEY)).thenReturn("current-key");
        when(message.getHeader("kafka.OVERRIDE_TOPIC")).thenReturn("unconfigured-topic");
        when(message.getHeader(KafkaConstants.OVERRIDE_TOPIC)).thenReturn("current-topic");

        new KafkaLegacyHeaderCompatibility(List.of("kafka.KEY"))
            .applyLegacyOverrides(message);

        verify(message, times(0)).setHeader(KafkaConstants.KEY, null);
        verify(message, times(0)).setHeader(KafkaConstants.OVERRIDE_TOPIC, "unconfigured-topic");
    }

    @Test
    void shouldCopyEmptyLegacyValueOverCurrentHeader() {
        Message message = mock(Message.class);
        when(message.getHeader("kafka.KEY")).thenReturn("");
        when(message.getHeader(KafkaConstants.KEY)).thenReturn("current-key");

        new KafkaLegacyHeaderCompatibility(List.of("kafka.KEY"))
            .applyLegacyOverrides(message);

        verify(message).setHeader(KafkaConstants.KEY, "");
    }

    @Test
    void shouldReadConfiguredLegacyHeaderForEveryRenamedProducerHeader() {
        Message message = mock(Message.class);
        when(message.getHeader("kafka.PARTITION_KEY")).thenReturn("4");
        when(message.getHeader("kafka.OVERRIDE_TOPIC")).thenReturn("legacy-topic");

        KafkaLegacyHeaderCompatibility compatibility = new KafkaLegacyHeaderCompatibility(
            List.of("kafka.PARTITION_KEY", "kafka.OVERRIDE_TOPIC"));

        assertEquals("4", compatibility.getHeaderValue(message, KafkaConstants.PARTITION_KEY));
        assertEquals("legacy-topic", compatibility.getHeaderValue(message, KafkaConstants.OVERRIDE_TOPIC));
    }

    @Test
    void shouldReadTypedLegacyHeaderAndRemoveBothNamesForProducerOverrides() {
        Message message = mock(Message.class);
        when(message.getHeader("kafka.PARTITION_KEY", Integer.class)).thenReturn(4);
        when(message.getHeader(KafkaConstants.OVERRIDE_TOPIC)).thenReturn(null);
        when(message.getHeader("kafka.OVERRIDE_TOPIC")).thenReturn("legacy-topic");

        KafkaLegacyHeaderCompatibility compatibility = new KafkaLegacyHeaderCompatibility(
            List.of("kafka.PARTITION_KEY", "kafka.OVERRIDE_TOPIC"));

        assertEquals(4,
            compatibility.getHeaderValue(message, KafkaConstants.PARTITION_KEY, Integer.class));
        assertEquals("legacy-topic",
            compatibility.removeHeaderValue(message, KafkaConstants.OVERRIDE_TOPIC));
        verify(message).removeHeader(KafkaConstants.OVERRIDE_TOPIC);
        verify(message).removeHeader("kafka.OVERRIDE_TOPIC");
    }

    @Test
    void shouldReadOnlyConfiguredHeadersAndPreserveEmptyValues() {
        Message message = mock(Message.class);
        when(message.getHeader(KafkaConstants.KEY)).thenReturn("");

        new KafkaLegacyHeaderCompatibility(List.of("kafka.KEY", "kafka.KEY"))
            .apply(message);

        verify(message, times(1)).getHeader(KafkaConstants.KEY);
        verify(message).setHeader("kafka.KEY", "");
        verify(message, times(0)).getHeader(KafkaConstants.TOPIC);
        verify(message, times(0)).getHeader(KafkaConstants.HEADERS);
    }

    @Test
    void shouldReadConfiguredHeaderOnceWhenValueIsNull() {
        Message message = mock(Message.class);

        new KafkaLegacyHeaderCompatibility(List.of("kafka.KEY")).apply(message);

        verify(message, times(1)).getHeader(KafkaConstants.KEY);
        verify(message).setHeader("kafka.KEY", null);
        verify(message, times(0)).getHeader(KafkaConstants.TOPIC);
        verify(message, times(0)).getHeader(KafkaConstants.HEADERS);
    }

    @Test
    void shouldReadLegacyHeaderWhenCurrentHeaderIsMissing() {
        Message message = mock(Message.class);
        when(message.getHeader(KafkaConstants.KEY)).thenReturn(null);
        when(message.getHeader("kafka.KEY")).thenReturn("legacy-key");

        Object value = new KafkaLegacyHeaderCompatibility(List.of("kafka.KEY"))
            .getHeaderValue(message, KafkaConstants.KEY);

        org.junit.jupiter.api.Assertions.assertEquals("legacy-key", value);
        verify(message, times(1)).getHeader(KafkaConstants.KEY);
        verify(message, times(1)).getHeader("kafka.KEY");
    }

    @Test
    void shouldPreferCurrentHeaderAndPreserveEmptyLegacyValue() {
        Message message = mock(Message.class);
        when(message.getHeader(KafkaConstants.KEY)).thenReturn("current-key");

        Object value = new KafkaLegacyHeaderCompatibility(List.of("kafka.KEY"))
            .getHeaderValue(message, KafkaConstants.KEY);

        org.junit.jupiter.api.Assertions.assertEquals("current-key", value);
        verify(message, times(1)).getHeader(KafkaConstants.KEY);
        verify(message, times(0)).getHeader("kafka.KEY");

        Message emptyValueMessage = mock(Message.class);
        when(emptyValueMessage.getHeader("kafka.KEY")).thenReturn("");
        Object emptyValue = new KafkaLegacyHeaderCompatibility(List.of("kafka.KEY"))
            .getHeaderValue(emptyValueMessage, KafkaConstants.KEY);

        org.junit.jupiter.api.Assertions.assertEquals("", emptyValue);
        verify(emptyValueMessage, times(1)).getHeader(KafkaConstants.KEY);
        verify(emptyValueMessage, times(1)).getHeader("kafka.KEY");
    }
}
