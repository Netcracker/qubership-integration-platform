package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.runtime.catalog.util.TriggerUtils.getSdsTriggerJobId;

@Component
public class SdsTriggerInfoBeanBuilder implements ElementBeansBuilder {
    @Override
    public boolean applicableTo(ChainElement element) {
        return CamelNames.SDS_TRIGGER_COMPONENT.equals(element.getType());
    }

    @Override
    public void build(XMLStreamWriter2 streamWriter, ChainElement element, SourceBuilderContext context) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", "SdsTriggerInfo-" + element.getId());
        streamWriter.writeAttribute("type", "org.qubership.integration.platform.engine.metadata.SdsTriggerInfo");

        streamWriter.writeStartElement("constructors");

        streamWriter.writeEmptyElement("constructor");
        streamWriter.writeAttribute("index", "0");
        streamWriter.writeAttribute("value", getSdsTriggerJobId(element));

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }
}
