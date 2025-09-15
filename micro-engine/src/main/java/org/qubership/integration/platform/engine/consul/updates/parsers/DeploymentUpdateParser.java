package org.qubership.integration.platform.engine.consul.updates.parsers;

import io.vertx.ext.consul.KeyValue;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.function.Function;

@ApplicationScoped
public class DeploymentUpdateParser implements Function<List<KeyValue>, Long> {
    @Override
    public Long apply(List<KeyValue> entries) {
        return switch (entries.size()) {
            case 0 -> 0L;
            case 1 -> {
                String value = entries.getFirst().getValue();
                String decodedValue = DecodeUtil.decodeValue(value);
                yield StringUtils.isBlank(decodedValue)
                        ? 0L
                        : Long.parseLong(decodedValue);
            }
            default -> throw new RuntimeException(
                    "Invalid number of deployment update entries: "
                            + entries.size());
        };
    }
}
