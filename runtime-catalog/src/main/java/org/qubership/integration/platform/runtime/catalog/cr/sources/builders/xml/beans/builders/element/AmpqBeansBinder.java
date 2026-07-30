package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element.helpers.MaasClassifierHelper;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;

import static org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.XmlBeanConstants.*;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.*;

@Component
public class AmpqBeansBinder implements ElementBeansBuilder {
    private static final Set<String> RABBITMQ_ELEMENTS = Set.of(
                CamelNames.RABBITMQ_SENDER_COMPONENT,
                CamelNames.RABBITMQ_SENDER_2_COMPONENT,
                CamelNames.RABBITMQ_TRIGGER_COMPONENT,
                CamelNames.RABBITMQ_TRIGGER_2_COMPONENT
        );

    private final MaasClassifierHelper maasClassifierHelper;

    @Autowired
    public AmpqBeansBinder(
            MaasClassifierHelper maasClassifierHelper
    ) {
        this.maasClassifierHelper = maasClassifierHelper;
    }

    @Override
    public boolean applicableTo(ChainElement element) {
        String type = element.getType();
        return RABBITMQ_ELEMENTS.contains(type)
                || (
                        Set.of(ASYNC_API_TRIGGER_COMPONENT, SERVICE_CALL_COMPONENT).contains(type)
                        && OPERATION_PROTOCOL_TYPE_AMQP.equals(
                                element.getProperties().get(OPERATION_PROTOCOL_TYPE_PROP))
        );
    }

    @Override
    public void build(XMLStreamWriter2 streamWriter, ChainElement element, SourceBuilderContext context) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", element.getId());
        streamWriter.writeAttribute("type", "com.rabbitmq.client.MetricsCollector");
        streamWriter.writeAttribute("builderClass", "org.qubership.integration.platform.engine.util.builders.RabbitMQMetricsCollectorBuilder");
        streamWriter.writeAttribute("builderMethod", "build");

        streamWriter.writeStartElement("properties");

        Chain chain = element.getSnapshot().getChain();

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "chainId");
        streamWriter.writeAttribute(ATTR_VALUE, chain.getId());

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "chainName");
        streamWriter.writeAttribute(ATTR_VALUE, chain.getName());

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "elementId");
        streamWriter.writeAttribute(ATTR_VALUE, element.getOriginalId());

        streamWriter.writeEmptyElement(XML_PROPERTY);
        streamWriter.writeAttribute(ATTR_KEY, "elementName");
        streamWriter.writeAttribute(ATTR_VALUE, element.getName());

        String maasClassifier = getMaasClassifier(element);
        boolean useMaas = StringUtils.isNotBlank(maasClassifier);
        if (useMaas) {
            streamWriter.writeEmptyElement(XML_PROPERTY);
            streamWriter.writeAttribute(ATTR_KEY, "maasClassifier");
            streamWriter.writeAttribute(ATTR_VALUE, maasClassifier);
        }

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();

        if (useMaas) {
            maasClassifierHelper.writeMaasClassifierInfoBean(streamWriter, element, OPERATION_PROTOCOL_TYPE_AMQP, maasClassifier);
        }
    }

    private String getMaasClassifier(ChainElement element) {
        return RABBITMQ_ELEMENTS.contains(element.getType())
            ? maasClassifierHelper.getMaasClassifierForAmpqElement(element)
            : maasClassifierHelper.getMaasClassifierForServiceCallOrAsyncApiElement(element);
    }
}
