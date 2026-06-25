package org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.camelk.sources.SourceBuilderContext;
import org.qubership.integration.platform.camelk.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.chain.model.Chain;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.library.constants.ConfigurationPropertiesConstants.HTTP_TRIGGER_ELEMENT;

@Component
public class HttpTriggerBeansBuilder implements ElementBeansBuilder {
    @Override
    public boolean applicableTo(Element element) {
        String type = element.getType();
        return HTTP_TRIGGER_ELEMENT.equals(type);
    }

    @Override
    public void build(
            XMLStreamWriter2 streamWriter,
            Element element,
            SourceBuilderContext context
    ) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", element.getId());
        streamWriter.writeAttribute("type", "org.qubership.integration.platform.engine.camel.components.servlet.ServletTagsProvider");
        streamWriter.writeAttribute("builderClass", "org.qubership.integration.platform.engine.util.builders.ServletTagsProviderBuilder");
        streamWriter.writeAttribute("builderMethod", "build");

        streamWriter.writeStartElement("properties");

        Chain chain = element.getSnapshot().map(Snapshot::getChain)
            .orElseThrow(() -> new RuntimeException("Failed to get chain from snapshot"));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "chainId");
        streamWriter.writeAttribute("value", chain.getId());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "chainName");
        streamWriter.writeAttribute("value", chain.getName());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "elementId");
        streamWriter.writeAttribute("value", element.getOriginalId().orElse(element.getId()));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "elementName");
        streamWriter.writeAttribute("value", element.getName());

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }
}
