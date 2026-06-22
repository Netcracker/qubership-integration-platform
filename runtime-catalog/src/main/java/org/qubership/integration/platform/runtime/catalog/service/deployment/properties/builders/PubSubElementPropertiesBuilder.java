package org.qubership.integration.platform.runtime.catalog.service.deployment.properties.builders;

import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.library.constants.CamelNames;
import org.qubership.integration.platform.library.constants.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.qubership.integration.platform.util.ElementUtils.getPropertyAsString;

@Component
public class PubSubElementPropertiesBuilder implements ElementPropertiesBuilder {

    @Override
    public boolean applicableTo(Element element) {
        return Set.of(
                CamelNames.PUBSUB_TRIGGER_COMPONENT,
                CamelNames.PUBSUB_SENDER_COMPONENT
        ).contains(element.getType());
    }

    @Override
    public Map<String, String> build(Element element) {
        return buildPubSubConnectionProperties(
                getPropertyAsString(element, CamelOptions.PROJECT_ID),
                getPropertyAsString(element, CamelOptions.DESTINATION_NAME),
                getPropertyAsString(element, CamelOptions.SERVICE_ACCOUNT_KEY));
    }

    public static Map<String, String> buildPubSubConnectionProperties(String projectId, String destinationName, String serviceAccountKey) {
        Map<String, String> properties = new HashMap<>();
        properties.put(CamelOptions.PROJECT_ID, projectId);
        properties.put(CamelOptions.DESTINATION_NAME, destinationName);
        properties.put(CamelOptions.SERVICE_ACCOUNT_KEY, serviceAccountKey);
        return properties;
    }
}
