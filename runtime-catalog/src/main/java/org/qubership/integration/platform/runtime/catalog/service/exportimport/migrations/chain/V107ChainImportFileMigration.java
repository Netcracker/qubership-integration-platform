package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

@Slf4j
@Component
public class V107ChainImportFileMigration extends ChainElementsPropertiesMigration {
    private static final Collection<ElementPropertiesMigrationRule> ELEMENT_PROPERTIES_MIGRATION_RULES = List.of(
            new ElementPropertiesMigrationRule(
                    elementHasType("async-api-trigger"),
                    V107ChainImportFileMigration::migrateAsyncApiTrigger
            ),
            new ElementPropertiesMigrationRule(
                    elementHasType("service-call"),
                    V107ChainImportFileMigration::migrateServiceCall
            )
    );

    @Override
    public int getVersion() {
        return 107;
    }

    @Override
    protected Collection<ElementPropertiesMigrationRule> getMigrationRules() {
        return ELEMENT_PROPERTIES_MIGRATION_RULES;
    }

    private static void migrateAsyncApiTrigger(ObjectNode properties) {
        JsonNode asyncValidationSchema = properties.path("asyncValidationSchema");
        if (asyncValidationSchema.isMissingNode()) {
            return;
        }
        JsonNode afterNode = properties.path("after");
        if (!afterNode.isArray() || afterNode.size() != 1) {
            return;
        }
        if (afterNode.get(0) instanceof ObjectNode firstAfterElement) {
            firstAfterElement.put("schema", asyncValidationSchema.asText());
            properties.remove("asyncValidationSchema");
        }
    }

    private static void migrateServiceCall(ObjectNode properties) {
        JsonNode afterValidation = properties.path("afterValidation");
        if (!afterValidation.isArray() || afterValidation.isEmpty()) {
            return;
        }
        for (JsonNode element : afterValidation) {
            if (element instanceof ObjectNode objectElement && objectElement.has("scheme")) {
                objectElement.set("schema", objectElement.remove("scheme"));
            }
        }
    }
}
