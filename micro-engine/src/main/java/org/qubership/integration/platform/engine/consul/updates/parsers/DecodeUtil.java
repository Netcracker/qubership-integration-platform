package org.qubership.integration.platform.engine.consul.updates.parsers;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class DecodeUtil {
    public static String decodeValue(String value) {
        return StringUtils.isBlank(value)
                ? value
                : new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
