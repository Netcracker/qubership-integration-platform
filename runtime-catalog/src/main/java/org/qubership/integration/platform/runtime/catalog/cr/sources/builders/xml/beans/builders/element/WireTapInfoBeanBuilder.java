package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

import static org.qubership.integration.platform.runtime.catalog.consul.ConfigurationPropertiesConstants.ASYNC_SPLIT_ELEMENT;

@Component
public class WireTapInfoBeanBuilder implements ElementBeansBuilder {
    @Override
    public boolean applicableTo(ChainElement element) {
        return hasAsyncSplitElementInInputDependencies(element);
    }

    @Override
    public void build(
            XMLStreamWriter2 streamWriter,
            ChainElement element,
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

    public String getWireTapId(ChainElement element) {
        return element.getInputDependencies().stream()
                .map(dependency -> dependency.getElementFrom().getOriginalId())
                .collect(Collectors.joining(","));
    }

    public boolean hasAsyncSplitElementInInputDependencies(ChainElement element) {
        return element.getInputDependencies().stream()
                .map(Dependency::getElementFrom)
                .map(ChainElement::getType)
                .anyMatch(ASYNC_SPLIT_ELEMENT::equals);
    }
}
