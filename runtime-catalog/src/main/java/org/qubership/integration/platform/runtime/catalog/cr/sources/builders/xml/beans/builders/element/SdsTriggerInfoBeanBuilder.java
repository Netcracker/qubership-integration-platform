package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.library.constants.CamelNames;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.XmlBeanConstants.*;
import static org.qubership.integration.platform.runtime.catalog.util.TriggerUtils.getSdsTriggerJobId;

@Component
public class SdsTriggerInfoBeanBuilder implements ElementBeansBuilder {
    private static final String PROHIBIT_PARALLEL_RUN_PROP = "prohibitParallelRun";
    private static final String PARALLEL_RUN_TIMEOUT_PROP = "parallelRunTimeout";
    private static final String CRON_PROP = "cron";

    @Override
    public boolean applicableTo(ChainElement element) {
        return CamelNames.SDS_TRIGGER_COMPONENT.equals(element.getType());
    }

    @Override
    public void build(XMLStreamWriter2 streamWriter, ChainElement element, SourceBuilderContext context) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", "SdsTriggerInfo-" + element.getId());
        streamWriter.writeAttribute("type", "org.qubership.integration.platform.engine.metadata.SdsTriggerInfo");

        streamWriter.writeStartElement("properties");

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "jobId");
        streamWriter.writeAttribute(ATTR_VALUE, getSdsTriggerJobId(element));

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, PROHIBIT_PARALLEL_RUN_PROP);
        streamWriter.writeAttribute(ATTR_VALUE, element.getPropertyAsString(PROHIBIT_PARALLEL_RUN_PROP));

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, PARALLEL_RUN_TIMEOUT_PROP);
        streamWriter.writeAttribute(ATTR_VALUE, element.getPropertyAsString(PARALLEL_RUN_TIMEOUT_PROP));

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, CRON_PROP);
        streamWriter.writeAttribute(ATTR_VALUE, element.getPropertyAsString(CRON_PROP));

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }
}
