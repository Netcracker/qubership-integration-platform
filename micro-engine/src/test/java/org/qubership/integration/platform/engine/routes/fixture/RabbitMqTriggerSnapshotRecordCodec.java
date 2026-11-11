package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.LongString;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RabbitMqTriggerSnapshotRecordCodec {
    private static final ObjectMapper MAPPER = ObjectMappers.getObjectMapper();

    private RabbitMqTriggerSnapshotRecordCodec() {
    }

    public static byte[] encode(Object body, Map<?, ?> options) throws Exception {
        if (options.containsKey("bodyBase64")) {
            return Base64.getDecoder().decode((String) options.get("bodyBase64"));
        }
        String text = body == null ? "" : body instanceof String value ? value : MAPPER.writeValueAsString(body);
        if (options.containsKey("bodyRepeat")) {
            text = text.repeat(((Number) options.get("bodyRepeat")).intValue());
        }
        return body instanceof byte[] bytes && !options.containsKey("bodyRepeat")
                ? bytes.clone() : text.getBytes(StandardCharsets.UTF_8);
    }

    static AMQP.BasicProperties properties(Map<?, ?> options, Map<String, Object> headers) {
        Map<?, ?> values = options.get("amqpProperties") instanceof Map<?, ?> properties ? properties : Map.of();
        AMQP.BasicProperties.Builder builder = new AMQP.BasicProperties.Builder().headers(headers)
                .deliveryMode(values.containsKey("deliveryMode") ? ((Number) values.get("deliveryMode")).intValue() : 2);
        if (values.containsKey("contentType")) {
            builder.contentType((String) values.get("contentType"));
        }
        if (values.containsKey("contentEncoding")) {
            builder.contentEncoding((String) values.get("contentEncoding"));
        }
        if (values.containsKey("priority")) {
            builder.priority(((Number) values.get("priority")).intValue());
        }
        if (values.containsKey("correlationId")) {
            builder.correlationId((String) values.get("correlationId"));
        }
        if (values.containsKey("replyTo")) {
            builder.replyTo((String) values.get("replyTo"));
        }
        if (values.containsKey("expiration")) {
            builder.expiration((String) values.get("expiration"));
        }
        if (values.containsKey("messageId")) {
            builder.messageId((String) values.get("messageId"));
        }
        if (values.containsKey("timestamp")) {
            builder.timestamp(new Date(((Number) values.get("timestamp")).longValue()));
        }
        if (values.containsKey("type")) {
            builder.type((String) values.get("type"));
        }
        if (values.containsKey("userId")) {
            builder.userId((String) values.get("userId"));
        }
        if (values.containsKey("appId")) {
            builder.appId((String) values.get("appId"));
        }
        if (values.containsKey("clusterId")) {
            builder.clusterId((String) values.get("clusterId"));
        }
        return builder.build();
    }

    static Map<String, Object> normalizeHeaders(Map<String, Object> headers) {
        Map<String, Object> result = new LinkedHashMap<>();
        headers.forEach((key, value) -> result.put(key, normalize(value)));
        return result;
    }

    private static Object normalize(Object value) {
        if (value instanceof Date timestamp) {
            return timestamp.getTime();
        }
        if (value instanceof Enum<?> enumeration) {
            return enumeration.name();
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof LongString text) {
            return text.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), normalize(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(item -> result.add(normalize(item)));
            return result;
        }
        return value;
    }

    static byte[] bodyBytes(Object body) {
        if (body instanceof byte[] bytes) {
            return bytes;
        }
        return body == null ? new byte[0] : String.valueOf(body).getBytes(StandardCharsets.UTF_8);
    }

    static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
