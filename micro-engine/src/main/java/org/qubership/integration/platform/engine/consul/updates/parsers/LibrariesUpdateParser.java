package org.qubership.integration.platform.engine.consul.updates.parsers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Identifier;
import io.vertx.ext.consul.KeyValue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.model.kafka.systemmodel.CompiledLibraryUpdate;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

@ApplicationScoped
public class LibrariesUpdateParser implements Function<List<KeyValue>, List<CompiledLibraryUpdate>> {
    @Inject
    @Identifier("jsonMapper")
    ObjectMapper objectMapper;

    @Override
    public List<CompiledLibraryUpdate> apply(List<KeyValue> entries) {
        return switch (entries.size()) {
            case 0 -> Collections.emptyList();
            case 1 -> {
                try {
                    String value = entries.getFirst().getValue();
                    yield StringUtils.isBlank(value)
                            ? Collections.emptyList()
                            : objectMapper.readValue(
                                value,
                                new TypeReference<>() {});
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }
            default -> throw new RuntimeException(
                    "Invalid number of library update values: "
                            + entries.size());
        };
    }
}
