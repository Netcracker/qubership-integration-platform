package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Consumer;

@Component
public class V103ChainImportFileMigration extends V102ChainImportFileMigration {
    private static final Map<String, Consumer<ObjectNode>> PROPERTY_MIGRATOR_MAP = Map.ofEntries(
            Map.entry("loop-2",
                    properties -> {
                        if (StringUtils.isBlank(properties.path("maxLoopIteration").asText())) {
                            properties.remove("maxLoopIteration");
                        }
                    }
            ),
            Map.entry("kafka-trigger-2",
                    properties -> {
                        if (StringUtils.isBlank(properties.path("reconnectBackoffMaxMs").asText())) {
                            properties.remove("reconnectBackoffMaxMs");
                        }
                    }
            )
    );

    @Override
    public int getVersion() {
        return 103;
    }

    @Override
    protected Map<String, Consumer<ObjectNode>> getMigratorMap() {
        return PROPERTY_MIGRATOR_MAP;
    }
}
