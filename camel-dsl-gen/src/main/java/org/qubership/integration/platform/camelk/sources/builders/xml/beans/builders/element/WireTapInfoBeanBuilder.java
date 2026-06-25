package org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.camelk.sources.SourceBuilderContext;
import org.qubership.integration.platform.camelk.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.chain.model.Connection;
import org.qubership.integration.platform.chain.model.Element;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.library.constants.ConfigurationPropertiesConstants.ASYNC_SPLIT_ELEMENT;

@Component
public class WireTapInfoBeanBuilder implements ElementBeansBuilder {
    @Override
    public boolean applicableTo(Element element) {
        return hasAsyncSplitElementInInputDependencies(element);
    }

    @Override
    public void build(
            XMLStreamWriter2 streamWriter,
            Element element,
            SourceBuilderContext context
    ) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", "WireTapInfo-" + element.getId());
        streamWriter.writeAttribute("type", "org.qubership.integration.platform.engine.metadata.WireTapInfo");

        streamWriter.writeStartElement("constructors");

        streamWriter.writeEmptyElement("constructor");
        streamWriter.writeAttribute("index", "0");
        streamWriter.writeAttribute("value", getWireTapId(element));

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }

    public String getWireTapId(Element element) {
        return element.getInputConnections().stream()
                .map(dependency -> dependency.getFrom().getOriginalId().orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.joining(","));
    }

    public boolean hasAsyncSplitElementInInputDependencies(Element element) {
        return element.getInputConnections().stream()
                .map(Connection::getFrom)
                .map(Element::getType)
                .anyMatch(ASYNC_SPLIT_ELEMENT::equals);
    }
}
