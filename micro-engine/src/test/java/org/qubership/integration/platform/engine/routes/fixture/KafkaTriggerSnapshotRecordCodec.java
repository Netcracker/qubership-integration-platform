package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.kafka.common.utils.Bytes;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public final class KafkaTriggerSnapshotRecordCodec {
    private KafkaTriggerSnapshotRecordCodec() {
    }

    public static byte[] encode(Object value, Object encoding) {
        String name = encodingName(encoding);
        if (value == null) {
            return null;
        }
        try {
            return switch (name) {
                case "string" -> {
                    if (!(value instanceof String text)) {
                        throw invalid(name);
                    }
                    yield text.getBytes(StandardCharsets.UTF_8);
                }
                case "bytes" -> bytes(value);
                case "buffer", "kafka-bytes" -> bytes(value);
                case "short" -> ByteBuffer.allocate(Short.BYTES).putShort(number(value, name).shortValueExact()).array();
                case "integer" -> ByteBuffer.allocate(Integer.BYTES).putInt(number(value, name).intValueExact()).array();
                case "long" -> ByteBuffer.allocate(Long.BYTES).putLong(number(value, name).longValueExact()).array();
                case "float" -> ByteBuffer.allocate(Float.BYTES).putFloat(Float.parseFloat(value.toString())).array();
                case "double" -> ByteBuffer.allocate(Double.BYTES).putDouble(Double.parseDouble(value.toString())).array();
                case "uuid" -> UUID.fromString(value.toString()).toString().getBytes(StandardCharsets.UTF_8);
                case "void" -> throw invalid(name);
                default -> throw new IllegalArgumentException("Unsupported Kafka trigger encoding: " + name);
            };
        } catch (ArithmeticException failure) {
            throw invalid(name);
        }
    }

    static Class<?> valueType(Object encoding) {
        return switch (encodingName(encoding)) {
            case "string" -> String.class;
            case "bytes" -> byte[].class;
            case "buffer" -> ByteBuffer.class;
            case "kafka-bytes" -> Bytes.class;
            case "short" -> Short.class;
            case "integer" -> Integer.class;
            case "long" -> Long.class;
            case "float" -> Float.class;
            case "double" -> Double.class;
            case "uuid" -> UUID.class;
            case "void" -> Void.class;
            default -> throw new IllegalArgumentException("Unsupported Kafka trigger encoding: " + encoding);
        };
    }

    private static String encodingName(Object encoding) {
        if (encoding == null) {
            return "string";
        }
        if (!(encoding instanceof String name) || !List.of("string", "bytes", "buffer", "kafka-bytes", "short",
                "integer", "long", "float", "double", "uuid", "void").contains(name)) {
            throw new IllegalArgumentException("Kafka trigger encoding must name a supported record type.");
        }
        return name;
    }

    private static byte[] bytes(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof ByteBuffer buffer) {
            ByteBuffer copy = buffer.duplicate();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            return bytes;
        }
        if (value instanceof Bytes bytes) {
            return bytes.get().clone();
        }
        if (!(value instanceof List<?> values)) {
            throw invalid("bytes");
        }
        byte[] result = new byte[values.size()];
        for (int index = 0; index < values.size(); index++) {
            int number = number(values.get(index), "bytes").intValueExact();
            if (number < 0 || number > 255) {
                throw invalid("bytes");
            }
            result[index] = (byte) number;
        }
        return result;
    }

    private static BigDecimal number(Object value, String encoding) {
        if (!(value instanceof Number number)) {
            throw invalid(encoding);
        }
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException failure) {
            throw invalid(encoding);
        }
    }

    private static IllegalArgumentException invalid(String encoding) {
        return new IllegalArgumentException("Kafka trigger value does not match " + encoding + " encoding.");
    }
}
