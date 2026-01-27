package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

@Slf4j
@Component
public class V106ChainImportFileMigration extends ChainElementsPropertiesMigration {
    private static final Collection<ElementPropertiesMigrationRule> ELEMENT_PROPERTIES_MIGRATION_RULES = List.of(
            new ElementPropertiesMigrationRule(
                    elementHasType("when").or(elementHasType("catch")),
                    properties -> changePropertyTypeFromStringToInteger(properties, "priorityNumber")
            ),
            new ElementPropertiesMigrationRule(
                    elementHasType("catch-2"),
                    properties -> changePropertyTypeFromStringToInteger(properties, "priority")
            ),
            new ElementPropertiesMigrationRule(
                    elementHasType("if"),
                    properties -> properties.remove("priorityNumber")
            )
    );

    @Override
    public int getVersion() {
        return 106;
    }

    @Override
    protected Collection<ElementPropertiesMigrationRule> getMigrationRules() {
        return ELEMENT_PROPERTIES_MIGRATION_RULES;
    }

    private static void changePropertyTypeFromStringToInteger(ObjectNode properties, String propertyName) {
        if (properties.path(propertyName) instanceof TextNode textNode) {
            try {
                Integer value = Integer.valueOf(textNode.asText());
                properties.set(propertyName, JsonNodeFactory.instance.numberNode(value));
            } catch (NumberFormatException e) {
                log.warn("Failed to convert {} value from string to integer", propertyName, e);
            }
        }
    }
}
