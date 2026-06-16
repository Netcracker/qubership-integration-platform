package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.qubership.integration.platform.library.constants.ConfigurationPropertiesConstants.MCP_TRIGGER_ELEMENT;

@Component
public class McpTriggerBeansBuilder implements ElementBeansBuilder {
    @Override
    public boolean applicableTo(ChainElement element) {
        String type = element.getType();
        return MCP_TRIGGER_ELEMENT.equals(type);
    }

    @Override
    public void build(XMLStreamWriter2 streamWriter, ChainElement element, SourceBuilderContext context) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", "McpTriggerInfo-" + element.getId());
        streamWriter.writeAttribute("type", "org.qubership.integration.platform.engine.metadata.McpTriggerInfo");

        streamWriter.writeStartElement("properties");

        Collection<String> propertyNames = List.of(
                "name",
                "title",
                "description",
                "inputSchema",
                "outputSchema",
                "readOnly",
                "destructive",
                "idempotent",
                "openWorld",
                "requiresLocal"
        );

        for (String propertyName : propertyNames) {
            streamWriter.writeEmptyElement("property");
            streamWriter.writeAttribute("key", propertyName);
            streamWriter.writeAttribute("value", Optional.ofNullable(element.getProperties().get(propertyName))
                    .map(String::valueOf)
                    .orElse(""));
        }

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }
}
