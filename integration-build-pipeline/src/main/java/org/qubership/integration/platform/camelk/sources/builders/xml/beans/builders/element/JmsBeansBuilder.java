package org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.camelk.sources.SourceBuilderContext;
import org.qubership.integration.platform.camelk.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.util.ElementUtils;
import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.camelk.sources.builders.xml.beans.BeanPropertyHelper.writePropertyElement;
import static org.qubership.integration.platform.library.constants.CamelNames.JMS_SENDER_COMPONENT;
import static org.qubership.integration.platform.library.constants.CamelNames.JMS_TRIGGER_COMPONENT;

@Component
public class JmsBeansBuilder implements ElementBeansBuilder {
    private static final String JMS_INITIAL_CONTEXT_FACTORY = "initialContextFactory";
    private static final String JMS_PROVIDER_URL = "providerUrl";
    private static final String JMS_CONNECTION_FACTORY_NAME = "connectionFactoryName";
    private static final String JMS_USERNAME = "username";
    private static final String JMS_PASSWORD = "password";

    @Override
    public boolean applicableTo(Element element) {
        String type = element.getType();
        return JMS_TRIGGER_COMPONENT.equals(type) || JMS_SENDER_COMPONENT.equals(type);
    }

    @Override
    public void build(XMLStreamWriter2 streamWriter, Element element, SourceBuilderContext context) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", "jms-" + element.getId());
        streamWriter.writeAttribute("type", "org.apache.camel.component.jms.JmsComponent");
        streamWriter.writeAttribute("builderClass", "org.qubership.integration.platform.engine.util.builders.JmsComponentBuilder");
        streamWriter.writeAttribute("builderMethod", "build");

        streamWriter.writeStartElement("properties");

        writePropertyElement(streamWriter, "elementId", element.getOriginalId().orElse(element.getId()));
        writePropertyElement(streamWriter, JMS_INITIAL_CONTEXT_FACTORY, ElementUtils.getPropertyAsString(element, JMS_INITIAL_CONTEXT_FACTORY));
        writePropertyElement(streamWriter, JMS_PROVIDER_URL, ElementUtils.getPropertyAsString(element, JMS_PROVIDER_URL));
        writePropertyElement(streamWriter, JMS_CONNECTION_FACTORY_NAME, ElementUtils.getPropertyAsString(element, JMS_CONNECTION_FACTORY_NAME));
        writePropertyElement(streamWriter, JMS_USERNAME, ElementUtils.getPropertyAsString(element, JMS_USERNAME));
        writePropertyElement(streamWriter,  JMS_PASSWORD, ElementUtils.getPropertyAsString(element, JMS_PASSWORD));

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }
}
