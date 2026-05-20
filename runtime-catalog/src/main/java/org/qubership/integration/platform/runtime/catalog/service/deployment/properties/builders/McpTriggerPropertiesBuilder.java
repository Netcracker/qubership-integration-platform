package org.qubership.integration.platform.runtime.catalog.service.deployment.properties.builders;

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.qubership.integration.platform.runtime.catalog.consul.ConfigurationPropertiesConstants.*;

@Component
public class McpTriggerPropertiesBuilder implements ElementPropertiesBuilder {
    @Override
    public boolean applicableTo(ChainElement element) {
        String type = element.getType();
        return MCP_TRIGGER_ELEMENT.equals(type);
    }

    @Override
    public Map<String, String> build(ChainElement element) {
        return Stream.of(
                "mcpServiceIds",
                "name",
                "title",
                "description",
                "inputSchema",
                "outputSchema",
                "readOnly",
                "destructive",
                "idempotent",
                "openWorld",
                "requiresLocal"
        ).collect(Collectors.toMap(
                Function.identity(),
                key -> Optional.ofNullable(element.getProperties().get(key))
                        .map(String::valueOf)
                        .orElse("")));
    }
}
