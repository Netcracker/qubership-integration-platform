package org.qubership.integration.platform.runtime.catalog.service.deployment.properties.builders;

import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Component
public class PubSubElementPropertiesBuilder implements ElementPropertiesBuilder {

    @Override
    public boolean applicableTo(ChainElement element) {
        return Set.of(
                CamelNames.PUBSUB_TRIGGER_COMPONENT,
                CamelNames.PUBSUB_SENDER_COMPONENT
        ).contains(element.getType());
    }

    @Override
    public Map<String, String> build(ChainElement element) {
        return buildPubSubConnectionProperties(element.getPropertyAsString(CamelOptions.PROJECT_ID),
                element.getPropertyAsString(CamelOptions.DESTINATION_NAME),
                element.getPropertyAsString(CamelOptions.SERVICE_ACCOUNT_KEY));
    }

    public static Map<String, String> buildPubSubConnectionProperties(String projectId, String destinationName, String serviceAccountKey) {
        Map<String, String> properties = new HashMap<>();
        properties.put(CamelOptions.PROJECT_ID, projectId);
        properties.put(CamelOptions.DESTINATION_NAME, destinationName);
        properties.put(CamelOptions.SERVICE_ACCOUNT_KEY, serviceAccountKey);
        return properties;
    }
}
