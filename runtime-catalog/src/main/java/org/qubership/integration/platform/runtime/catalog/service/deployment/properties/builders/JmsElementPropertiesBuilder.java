package org.qubership.integration.platform.runtime.catalog.service.deployment.properties.builders;

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.qubership.integration.platform.runtime.catalog.consul.ConfigurationPropertiesConstants.JMS_SENDER_ELEMENT;
import static org.qubership.integration.platform.runtime.catalog.consul.ConfigurationPropertiesConstants.JMS_TRIGGER_ELEMENT;

@Component
public class JmsElementPropertiesBuilder implements ElementPropertiesBuilder {

    public static final String JMS_INITIAL_CONTEXT_FACTORY = "initialContextFactory";
    public static final String JMS_PROVIDER_URL = "providerUrl";
    public static final String JMS_CONNECTION_FACTORY_NAME = "connectionFactoryName";
    public static final String JMS_USERNAME = "username";
    public static final String JMS_PASSWORD = "password";

    @Override
    public boolean applicableTo(ChainElement element) {
        String type = element.getType();
        return JMS_SENDER_ELEMENT.equals(type) || JMS_TRIGGER_ELEMENT.equals(type);
    }

    @Override
    public Map<String, String> build(ChainElement element) {
        return Stream.of(
                JMS_PROVIDER_URL,
                JMS_INITIAL_CONTEXT_FACTORY,
                JMS_CONNECTION_FACTORY_NAME,
                JMS_USERNAME,
                JMS_PASSWORD).map(key -> {
                    String value = element.getPropertyAsString(key);
                    return value != null ? Map.entry(key, value) : null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
