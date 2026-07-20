package org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.stax2.XMLStreamWriter2;
import org.qubership.integration.platform.camelk.sources.SourceBuilderContext;
import org.qubership.integration.platform.camelk.sources.builders.xml.beans.ElementBeansBuilder;
import org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element.helpers.MaasClassifierHelper;
import org.qubership.integration.platform.chain.model.Chain;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.library.constants.CamelNames;
import org.qubership.integration.platform.util.ElementUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import javax.xml.stream.XMLStreamException;

import static org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.XmlBeanConstants.*;
import static org.qubership.integration.platform.library.constants.CamelNames.*;
import static org.qubership.integration.platform.library.constants.CamelOptions.*;

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
    public boolean applicableTo(Element element) {
        String type = element.getType();
        return RABBITMQ_ELEMENTS.contains(type)
                || (
                        Set.of(ASYNC_API_TRIGGER_COMPONENT, SERVICE_CALL_COMPONENT).contains(type)
                        && OPERATION_PROTOCOL_TYPE_AMQP.equals(
                                element.getProperties().get(OPERATION_PROTOCOL_TYPE_PROP))
        );
    }

    @Override
    public void build(XMLStreamWriter2 streamWriter, Element element, SourceBuilderContext context) throws Exception {
        streamWriter.writeStartElement("bean");
        streamWriter.writeAttribute("name", element.getId());
        streamWriter.writeAttribute("type", "com.rabbitmq.client.MetricsCollector");
        streamWriter.writeAttribute("builderClass", "org.qubership.integration.platform.engine.util.builders.RabbitMQMetricsCollectorBuilder");
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

        String maasClassifier = getMaasClassifier(element, context);
        boolean useMaas = StringUtils.isNotBlank(maasClassifier);
        if (useMaas) {
            streamWriter.writeEmptyElement(XML_PROPERTY);
            streamWriter.writeAttribute(ATTR_KEY, "maasClassifier");
            streamWriter.writeAttribute(ATTR_VALUE, maasClassifier);
        }

        streamWriter.writeEndElement();
        streamWriter.writeEndElement();

        if (useMaas) {
            addMaasClassifierInfoBean(streamWriter, element, context);
        }
    }

    private void addMaasClassifierInfoBean(
            XMLStreamWriter2 streamWriter,
            Element element,
            SourceBuilderContext context
    ) throws XMLStreamException {
        String maasClassifier = getMaasClassifier(element, context);

        String namespace;
        String tenantId;
        String tenantEnabled;

        if (RABBITMQ_ELEMENTS.contains(element.getType())) {
            namespace = Optional.ofNullable(element.getProperties().get(MAAS_CLASSIFIER_NAMESPACE))
                    .map(Object::toString).orElse(null);
            tenantId = Optional.ofNullable(element.getProperties().get(MAAS_CLASSIFIER_TENANT_ID))
                    .map(Object::toString).orElse(null);
            tenantEnabled = Optional.ofNullable(element.getProperties().get(MAAS_CLASSIFIER_TENANT_ENABLED))
                    .map(Object::toString).orElse("false");
        } else { // Async API Trigger and Service Call elements
            namespace = String.valueOf(ElementUtils.extractOperationAsyncProperties(element.getProperties())
                    .get(CamelNames.MAAS_CLASSIFIER_NAMESPACE_PROP));
            tenantId = String.valueOf(ElementUtils.extractOperationAsyncProperties(element.getProperties())
                    .get(CamelNames.MAAS_CLASSIFIER_TENANT_ID_CAMEL_NAME));
            tenantEnabled = String.valueOf(ElementUtils.extractOperationAsyncProperties(element.getProperties())
                    .get(CamelNames.MAAS_CLASSIFIER_TENANT_ENABLED_CAMEL_NAME));
        }
        maasClassifierHelper.addMaasClassifierInfoBean(
                streamWriter,
                element,
                OPERATION_PROTOCOL_TYPE_AMQP,
                maasClassifier,
                namespace,
                tenantId,
                tenantEnabled
        );
    }

    private String getMaasClassifier(Element element, SourceBuilderContext context) {
        return RABBITMQ_ELEMENTS.contains(element.getType())
            ? maasClassifierHelper.getMaasClassifierForAmpqElement(element)
            : maasClassifierHelper.getMaasClassifierForServiceCallOrAsyncApiElement(
                    element, context.getIntegrationServiceCatalog());
    }
}
