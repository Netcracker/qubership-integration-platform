package org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.camelk.sources.SourceBuilderContext;
import org.qubership.integration.platform.camelk.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.chain.model.Chain;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.util.ElementUtils;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static org.qubership.integration.platform.camelk.sources.builders.xml.beans.BeanPropertyHelper.writePropertyElement;
import static org.qubership.integration.platform.library.constants.CamelNames.*;
import static org.qubership.integration.platform.library.constants.CamelOptions.CONTEXT_PATH;
import static org.qubership.integration.platform.library.constants.CamelOptions.OPERATION_PATH;

@Component
public class HttpSenderBeansBinder implements ElementBeansBuilder {
    @Override
    public boolean applicableTo(Element element) {
        String type = element.getType();
        return HTTP_SENDER_COMPONENT.equals(type)
                || GRAPHQL_SENDER_COMPONENT.equals(type)
                || (SERVICE_CALL_COMPONENT.equals(type)
                        && (OPERATION_PROTOCOL_TYPE_HTTP.equals(
                                ElementUtils.getPropertyAsString(element, OPERATION_PROTOCOL_TYPE_PROP))
                                || OPERATION_PROTOCOL_TYPE_GRAPHQL.equals(
                                        ElementUtils.getPropertyAsString(element, OPERATION_PROTOCOL_TYPE_PROP))));
    }

    @Override
    public void build(XMLStreamWriter2 streamWriter, Element element, SourceBuilderContext context) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", element.getId());
        streamWriter.writeAttribute("type", "org.apache.camel.component.http.HttpClientConfigurer");
        streamWriter.writeAttribute("builderClass", "org.qubership.integration.platform.engine.util.builders.HttpClientConfigurerBuilder");
        streamWriter.writeAttribute("builderMethod", "build");

        streamWriter.writeStartElement("properties");

        Chain chain = element.getSnapshot().map(Snapshot::getChain)
            .orElseThrow(() -> new RuntimeException("Failed to get chain from snapshot"));

        writePropertyElement(streamWriter, "chainId", chain.getId());
        writePropertyElement(streamWriter, "chainName", chain.getName());
        writePropertyElement(streamWriter, "elementId", element.getOriginalId().orElse(element.getId()));
        writePropertyElement(streamWriter, "elementName", element.getName());
        writePropertyElement(streamWriter, "operationPath", Optional.ofNullable(element.getProperties().get(CONTEXT_PATH))
            .or(() -> Optional.ofNullable(element.getProperties().get(OPERATION_PATH)))
            .map(Object::toString)
            .orElse("null"));
        writePropertyElement(streamWriter, "reuseEstablishedConnection", Optional.ofNullable(element.getProperties().get(REUSE_ESTABLISHED_CONN))
            .map(Object::toString)
            .orElse("true"));
        writePropertyElement(streamWriter, "protocol", Optional.ofNullable(element.getProperties().get(OPERATION_PROTOCOL_TYPE_PROP))
            .map(Object::toString)
            .orElse(OPERATION_PROTOCOL_TYPE_HTTP));

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }
}
