package org.qubership.integration.platform.io.readers.migrations.chain;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.List;


@Component
public class V109ChainImportFileMigration extends ChainElementsPropertiesMigration {
    private static final Collection<ElementPropertiesMigrationRule> ELEMENT_PROPERTIES_MIGRATION_RULES = List.of(
            new ElementPropertiesMigrationRule(
                    elementHasType("service-call"),
                    V109ChainImportFileMigration::migrateServiceCall
            )
    );

    @Override
    public int getVersion() {
        return 109;
    }

    @Override
    protected Collection<ElementPropertiesMigrationRule> getMigrationRules() {
        return ELEMENT_PROPERTIES_MIGRATION_RULES;
    }

    private static void migrateServiceCall(ObjectNode properties) {
        properties.remove("errorThrowing");
        JsonNode after = properties.get("after");
        if (after == null || after.isNull()) {
            properties.set("after", JsonNodeFactory.instance.arrayNode());
            return;
        }
        if (after instanceof ArrayNode items) {
            rewriteCodes(items);
        }
    }

    private static void rewriteCodes(ArrayNode items) {
        boolean hasDefault = false;
        for (JsonNode item : items) {
            hasDefault |= "default".equals(item.path("code").asText());
        }
        for (JsonNode item : items) {
            if (!(item instanceof ObjectNode handler)) {
                continue;
            }
            String code = handler.path("code").asText();
            if ("Default".equals(code) && !hasDefault) {
                handler.put("code", "default");
            } else if (code.matches("[1-5]xx") && !handler.path("wildcard").asBoolean(false)) {
                handler.put("code", code.charAt(0) + "00.." + code.charAt(0) + "99");
                handler.put("wildcard", true);
            }
        }
    }
}
