package org.qubership.integration.platform.engine.camel.components.kafka;

import org.apache.camel.Message;
import org.apache.camel.component.kafka.KafkaConstants;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class KafkaLegacyHeaderCompatibility {

    private static final Map<String, String> REPLACED_HEADERS = Map.of(
        "kafka.KEY", KafkaConstants.KEY,
        "kafka.TOPIC", KafkaConstants.TOPIC,
        "kafka.HEADERS", KafkaConstants.HEADERS,
        "kafka.PARTITION_KEY", KafkaConstants.PARTITION_KEY,
        "kafka.PARTITION", KafkaConstants.PARTITION,
        "kafka.OVERRIDE_TOPIC", KafkaConstants.OVERRIDE_TOPIC,
        "kafka.OFFSET", KafkaConstants.OFFSET
    );

    private final List<String> legacyHeaderNames;

    public KafkaLegacyHeaderCompatibility(List<String> legacyHeaderNames) {
        this.legacyHeaderNames = List.copyOf(new LinkedHashSet<>(legacyHeaderNames));
    }

    public void apply(Message message) {
        for (String legacyHeaderName : legacyHeaderNames) {
            String currentHeaderName = REPLACED_HEADERS.get(legacyHeaderName);
            if (currentHeaderName != null) {
                Object value = message.getHeader(currentHeaderName);
                message.setHeader(legacyHeaderName, value);
            }
        }
    }

    public Object getHeaderValue(Message message, String currentHeaderName) {
        Object value = message.getHeader(currentHeaderName);
        if (value != null) {
            return value;
        }

        for (String legacyHeaderName : legacyHeaderNames) {
            if (currentHeaderName.equals(REPLACED_HEADERS.get(legacyHeaderName))) {
                return message.getHeader(legacyHeaderName);
            }
        }

        return null;
    }

    public <T> T getHeaderValue(Message message, String currentHeaderName, Class<T> type) {
        Object value = message.getHeader(currentHeaderName);
        if (value != null) {
            return message.getHeader(currentHeaderName, type);
        }

        for (String legacyHeaderName : legacyHeaderNames) {
            if (currentHeaderName.equals(REPLACED_HEADERS.get(legacyHeaderName))) {
                return message.getHeader(legacyHeaderName, type);
            }
        }

        return null;
    }

    public Object removeHeaderValue(Message message, String currentHeaderName) {
        Object value = getHeaderValue(message, currentHeaderName);
        message.removeHeader(currentHeaderName);
        for (String legacyHeaderName : legacyHeaderNames) {
            if (currentHeaderName.equals(REPLACED_HEADERS.get(legacyHeaderName))) {
                message.removeHeader(legacyHeaderName);
            }
        }
        return value;
    }
}
