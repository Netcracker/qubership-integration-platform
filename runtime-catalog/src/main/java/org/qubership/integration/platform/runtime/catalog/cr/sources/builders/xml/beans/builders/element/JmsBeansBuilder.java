package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.JMS_SENDER_COMPONENT;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.JMS_TRIGGER_COMPONENT;

@Component
public class JmsBeansBuilder implements ElementBeansBuilder {
    private static final String JMS_INITIAL_CONTEXT_FACTORY = "initialContextFactory";
    private static final String JMS_PROVIDER_URL = "providerUrl";
    private static final String JMS_CONNECTION_FACTORY_NAME = "connectionFactoryName";
    private static final String JMS_USERNAME = "username";
    private static final String JMS_PASSWORD = "password";

    @Override
    public boolean applicableTo(ChainElement element) {
        String type = element.getType();
        return JMS_TRIGGER_COMPONENT.equals(type) || JMS_SENDER_COMPONENT.equals(type);
    }

    @Override
    public void build(XMLStreamWriter2 streamWriter, ChainElement element, SourceBuilderContext context) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", "jms-" + element.getId());
        streamWriter.writeAttribute("type", "org.apache.camel.component.jms.JmsComponent");
        streamWriter.writeAttribute("builderClass", "org.qubership.integration.platform.engine.util.builders.JmsComponentBuilder");
        streamWriter.writeAttribute("builderMethod", "build");

        streamWriter.writeStartElement("properties");

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "elementId");
        streamWriter.writeAttribute("value", element.getOriginalId());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "initialContextFactory");
        streamWriter.writeAttribute("value", element.getPropertyAsString(JMS_INITIAL_CONTEXT_FACTORY));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "providerUrl");
        streamWriter.writeAttribute("value", element.getPropertyAsString(JMS_PROVIDER_URL));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "connectionFactoryName");
        streamWriter.writeAttribute("value", element.getPropertyAsString(JMS_CONNECTION_FACTORY_NAME));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "username");
        streamWriter.writeAttribute("value", element.getPropertyAsString(JMS_USERNAME));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "password");
        streamWriter.writeAttribute("value", element.getPropertyAsString(JMS_PASSWORD));

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }
}
