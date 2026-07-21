package org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.camelk.sources.SourceBuilderContext;
import org.qubership.integration.platform.camelk.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.chain.model.Chain;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.util.ElementUtils;
import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.camelk.sources.builders.xml.beans.XmlBeanConstants.*;
import static org.qubership.integration.platform.library.constants.CamelNames.*;

@Component
public class GrpcBeansBuilder implements ElementBeansBuilder {
    @Override
    public boolean applicableTo(Element element) {
        return SERVICE_CALL_COMPONENT.equals(element.getType())
                && OPERATION_PROTOCOL_TYPE_GRPC.equals(
                        ElementUtils.getPropertyAsString(element, OPERATION_PROTOCOL_TYPE_PROP));
    }

    @Override
    public void build(XMLStreamWriter2 streamWriter, Element element, SourceBuilderContext context) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", element.getId());
        streamWriter.writeAttribute("type", "io.micrometer.core.instrument.binder.grpc.MetricCollectingClientInterceptor");
        streamWriter.writeAttribute("builderClass", "org.qubership.integration.platform.engine.util.builders.GrpcMetricCollectingClientInspectorBuilder");
        streamWriter.writeAttribute("builderMethod", "build");

        streamWriter.writeStartElement("properties");

        Chain chain = element.getSnapshot().map(Snapshot::getChain)
            .orElseThrow(() -> new RuntimeException("Failed to get chain from snapshot"));

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "chainId");
        streamWriter.writeAttribute(ATTR_VALUE, chain.getId());

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "chainName");
        streamWriter.writeAttribute(ATTR_VALUE, chain.getName());

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "elementId");
        streamWriter.writeAttribute(ATTR_VALUE, element.getOriginalId().orElse(element.getId()));

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "elementName");
        streamWriter.writeAttribute(ATTR_VALUE, element.getName());

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }
}
