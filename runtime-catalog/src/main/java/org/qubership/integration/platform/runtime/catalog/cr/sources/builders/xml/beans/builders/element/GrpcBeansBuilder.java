package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.*;

@Component
public class GrpcBeansBuilder implements ElementBeansBuilder {
    @Override
    public boolean applicableTo(ChainElement element) {
        return SERVICE_CALL_COMPONENT.equals(element.getType())
                && OPERATION_PROTOCOL_TYPE_GRPC.equals(
                        element.getPropertyAsString(OPERATION_PROTOCOL_TYPE_PROP));
    }

    @Override
    public void build(XMLStreamWriter2 streamWriter, ChainElement element, SourceBuilderContext context) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", element.getId());
        streamWriter.writeAttribute("type", "io.micrometer.core.instrument.binder.grpc.MetricCollectingClientInterceptor");
        streamWriter.writeAttribute("builderClass", "org.qubership.integration.platform.engine.util.builders.GrpcMetricCollectingClientInspectorBuilder");
        streamWriter.writeAttribute("builderMethod", "build");

        streamWriter.writeStartElement("properties");

        Chain chain = element.getSnapshot().getChain();

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "chainId");
        streamWriter.writeAttribute("value", chain.getId());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "chainName");
        streamWriter.writeAttribute("value", chain.getName());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "elementId");
        streamWriter.writeAttribute("value", element.getOriginalId());

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "elementName");
        streamWriter.writeAttribute("value", element.getName());

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }
}
