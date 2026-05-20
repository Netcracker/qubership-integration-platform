package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element;

import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.*;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.CONTEXT_PATH;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.OPERATION_PATH;

@Component
public class HttpSenderBeansBinder implements ElementBeansBuilder {
    @Override
    public boolean applicableTo(ChainElement element) {
        String type = element.getType();
        return HTTP_SENDER_COMPONENT.equals(type)
                || GRAPHQL_SENDER_COMPONENT.equals(type)
                || (SERVICE_CALL_COMPONENT.equals(type)
                        && (OPERATION_PROTOCOL_TYPE_HTTP.equals(
                                element.getPropertyAsString(OPERATION_PROTOCOL_TYPE_PROP))
                                || OPERATION_PROTOCOL_TYPE_GRAPHQL.equals(
                                        element.getPropertyAsString(OPERATION_PROTOCOL_TYPE_PROP))));
    }

    @Override
    public void build(XMLStreamWriter2 streamWriter, ChainElement element, SourceBuilderContext context) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", element.getId());
        streamWriter.writeAttribute("type", "org.apache.camel.component.http.HttpClientConfigurer");
        streamWriter.writeAttribute("builderClass", "org.qubership.integration.platform.engine.util.builders.HttpClientConfigurerBuilder");
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

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "operationPath");
        streamWriter.writeAttribute("value", Optional.ofNullable(element.getProperty(CONTEXT_PATH))
                .or(() -> Optional.ofNullable(element.getProperty(OPERATION_PATH)))
                        .map(Object::toString)
                        .orElse("null"));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "reuseEstablishedConnection");
        streamWriter.writeAttribute("value", Optional.ofNullable(element.getProperty(REUSE_ESTABLISHED_CONN))
                .map(Object::toString)
                .orElse("true"));

        streamWriter.writeEmptyElement("property");
        streamWriter.writeAttribute("key", "protocol");
        streamWriter.writeAttribute("value", Optional.ofNullable(element.getProperty(OPERATION_PROTOCOL_TYPE_PROP))
                .map(Object::toString)
                .orElse(OPERATION_PROTOCOL_TYPE_HTTP));

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();
    }
}
