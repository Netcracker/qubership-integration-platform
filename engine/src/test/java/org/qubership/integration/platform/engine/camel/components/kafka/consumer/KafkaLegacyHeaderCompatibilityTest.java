package org.qubership.integration.platform.engine.camel.components.kafka.consumer;

import org.apache.camel.Message;
import org.apache.camel.component.kafka.KafkaConstants;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaLegacyHeaderCompatibility;

import java.util.List;

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

        new KafkaLegacyHeaderCompatibility(List.of("kafka.KEY", "kafka.TOPIC", "kafka.HEADERS"))
            .apply(message);

        verify(message, times(1)).getHeader(KafkaConstants.KEY);
        verify(message, times(1)).getHeader(KafkaConstants.TOPIC);
        verify(message, times(1)).getHeader(KafkaConstants.HEADERS);
        verify(message).setHeader("kafka.KEY", "record-key");
        verify(message).setHeader("kafka.TOPIC", "record-topic");
        verify(message).setHeader("kafka.HEADERS", "record-headers");
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
